package com.unyx.atlas.feature.connect.conversation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unyx.atlas.chatremote.ChatMessagingRepository
import com.unyx.atlas.chatremote.ChatPairingException
import com.unyx.atlas.chatstorage.ChatAvatarStore
import com.unyx.atlas.chatstorage.ChatContactStore
import com.unyx.atlas.chatstorage.ChatConversationStore
import com.unyx.atlas.chatstorage.ChatMessageDirection
import com.unyx.atlas.chatstorage.ChatMessageHistoryStore
import com.unyx.atlas.chatstorage.ChatMessageRecord
import com.unyx.atlas.feature.connect.model.MessageBubbleModel
import com.unyx.atlas.feature.connect.model.MessageDeliveryState
import com.unyx.atlas.feature.connect.model.MessageDirection
import com.unyx.atlas.feature.connect.model.ReplyPreview
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

private const val INCOMING_POLL_INTERVAL_MILLIS = 4000L

/** Plan §15 "Proposta de opções: uma hora, oito horas e até reativar." */
enum class MuteOption(val durationSeconds: Long?) {
    ONE_HOUR(3600L),
    EIGHT_HOURS(8 * 3600L),
    UNTIL_REACTIVATED(null), // null here means "no duration to add" — resolved to Long.MAX_VALUE below, not "unmuted".
    UNMUTE(0L),
}

/**
 * One open conversation — reads/writes through [ChatMessagingRepository] (the real
 * cipher+network layer) and the two local stores that make a transcript possible at all
 * ([ChatMessageHistoryStore] for what to show, [ChatConversationStore] for which sent sequences
 * are still unconfirmed). No navigation args system exists (`feature:connect` has no NavHost —
 * see `AtlasConnectScreen`'s own doc), so [open] is called explicitly once the caller knows which
 * conversation this is, instead of a constructor/SavedStateHandle argument.
 */
@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val messagingRepository: ChatMessagingRepository,
    private val historyStore: ChatMessageHistoryStore,
    private val sessionStore: ChatConversationStore,
    private val contactStore: ChatContactStore,
    private val avatarStore: ChatAvatarStore,
) : ViewModel() {

    private var conversationId: String? = null

    var contactName by mutableStateOf("")
        private set
    var contactAvatarBytes by mutableStateOf<ByteArray?>(null)
        private set
    var messages by mutableStateOf<List<MessageBubbleModel>>(emptyList())
        private set
    var draft by mutableStateOf("")
    var isSending by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    /** Plan §11 "Responder a uma mensagem" — set by the screen when the user picks "Responder"
     *  from a message's action menu; shown above the composer until [send] consumes it or the
     *  user cancels via [clearReplyTarget]. */
    var replyTarget by mutableStateOf<MessageBubbleModel?>(null)
        private set
    /** Plan §13 "Bloquear" — refreshed on [open] and every poll tick (cheap local read, same
     *  trade-off the receipt-driven reload already makes) so a block flipped from the contact
     *  detail screen takes effect here without requiring this screen to be reopened. */
    var isBlocked by mutableStateOf(false)
        private set
    /** Plan §15 "Silenciar notificações" — read-only reflection of
     *  [com.unyx.atlas.chatstorage.ChatContact.mutedUntilEpochSeconds]; refreshed the same places
     *  [isBlocked] is. This screen never gates sending/receiving on it (unlike [isBlocked]) —
     *  muting "não impede sincronização", it only suppresses [ChatNotificationWorker]'s own
     *  notification, which this screen has no direct connection to. */
    var isMuted by mutableStateOf(false)
        private set
    /** Plan §13/§14 — "Buscar nesta conversa". [isSearching] just toggles the search field's
     *  visibility; the actual filtering happens in [visibleMessages], over the already-loaded
     *  [messages] — no separate index or store query, this is a prototype-scale local transcript
     *  (same trade-off [ChatMessageHistoryStore] itself already documents). */
    var isSearching by mutableStateOf(false)
        private set
    var searchQuery by mutableStateOf("")

    val visibleMessages: List<MessageBubbleModel>
        get() = if (searchQuery.isBlank()) messages else messages.filter { it.text.contains(searchQuery, ignoreCase = true) }

    private var pollingJob: Job? = null

    /** Idempotent — calling it again for the same [conversationId] (e.g. the screen recomposing)
     *  is a no-op, not a second poll loop. */
    fun open(conversationId: String) {
        if (this.conversationId == conversationId) return
        this.conversationId = conversationId
        refreshContactState()
        reloadLocalHistory()
        startIncomingPoll()
    }

    private fun refreshContactState() {
        val id = conversationId ?: return
        val contact = contactStore.load(id)
        contactName = contact?.displayName ?: "Conexão"
        isBlocked = contact?.isBlocked == true
        isMuted = contact?.isMuted(System.currentTimeMillis() / 1000) == true
        contactAvatarBytes = avatarStore.loadForContact(id)
    }

    /** Plan §15 — [MuteOption.UNMUTE] clears the preference entirely (not a zero-length mute),
     *  [MuteOption.UNTIL_REACTIVATED] stores `Long.MAX_VALUE` (see
     *  [com.unyx.atlas.chatstorage.ChatContact.mutedUntilEpochSeconds]'s own doc on why that
     *  models "até reativar" without a separate tri-state field), the two fixed durations add
     *  their seconds to now. */
    fun setMute(option: MuteOption) {
        val id = conversationId ?: return
        val until = when (option) {
            MuteOption.UNMUTE -> null
            MuteOption.UNTIL_REACTIVATED -> Long.MAX_VALUE
            else -> System.currentTimeMillis() / 1000 + (option.durationSeconds ?: 0L)
        }
        contactStore.setMutedUntil(id, until)
        refreshContactState()
    }

    private fun reloadLocalHistory() {
        val id = conversationId ?: return
        val pendingSequences = sessionStore.pendingOutbox(id).map { it.sequence }.toSet()
        val deliveredSequences = sessionStore.deliveredSentSequences(id)
        val records = historyStore.loadAll(id)
        // Keyed exactly like MessageBubbleModel.messageId/ChatMessageRecord.replyToMessageId so a
        // reply reference resolves with a plain map lookup, no second identifier scheme.
        val byMessageId = records.associateBy { "${it.direction.name}-${it.sequence}" }
        messages = records.map { it.toBubbleModel(pendingSequences, deliveredSequences, byMessageId) }
    }

    private fun ChatMessageRecord.toBubbleModel(
        pendingSentSequences: Set<Long>,
        deliveredSentSequences: Set<Long>,
        byMessageId: Map<String, ChatMessageRecord>,
    ): MessageBubbleModel {
        val isSent = direction == ChatMessageDirection.SENT
        return MessageBubbleModel(
            messageId = "${direction.name}-$sequence",
            text = text,
            direction = if (isSent) MessageDirection.SENT else MessageDirection.RECEIVED,
            timeLabel = formatTimeLabel(timestampEpochSeconds),
            deliveryState = when {
                !isSent -> null
                sequence in pendingSentSequences -> MessageDeliveryState.PENDING_LOCAL
                // An E2EE receipt (plan §12.3) is the only honest proof of "Entregue" — the
                // other side decrypted and persisted it, not just that the server accepted the
                // envelope. Until it arrives, "Enviada" (SERVER_ACCEPTED) is as far as this can claim.
                sequence in deliveredSentSequences -> MessageDeliveryState.DELIVERED
                else -> MessageDeliveryState.SERVER_ACCEPTED
            },
            replyPreview = replyToMessageId?.let { refId ->
                // The author is derivable straight from the reference's own direction prefix —
                // SENT-* always means "a message I sent", regardless of whether that original
                // record still exists to look up. The snippet, on the other hand, genuinely needs
                // the original record — null here (plan §12: deleted locally) renders as "Mensagem
                // indisponível", exactly the plan §11 wording for an unresolvable reference.
                val authorLabel = if (refId.startsWith("${ChatMessageDirection.SENT.name}-")) "Você" else contactName
                ReplyPreview(authorLabel = authorLabel, snippet = byMessageId[refId]?.text)
            },
        )
    }

    private fun formatTimeLabel(epochSeconds: Long): String =
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(epochSeconds))

    fun send() {
        val id = conversationId ?: return
        val text = draft.trim()
        if (text.isEmpty() || isSending) return
        if (isBlocked) {
            errorMessage = "Esta conexão está bloqueada — desbloqueie nos detalhes da conexão para enviar mensagens."
            return
        }
        val pendingReply = replyTarget
        draft = ""
        replyTarget = null
        isSending = true
        errorMessage = null
        viewModelScope.launch {
            try {
                // send() always completes its local half (encrypt + persist) even if the network
                // leg fails silently underneath — see ChatMessagingRepository.send's own doc. The
                // returned envelope's sequence is what lets this screen show the message
                // immediately, before knowing whether the server has it yet.
                val envelope = messagingRepository.sendText(id, text, pendingReply?.messageId)
                historyStore.append(
                    id,
                    ChatMessageRecord(
                        sequence = envelope.sequence, direction = ChatMessageDirection.SENT,
                        text = text, timestampEpochSeconds = System.currentTimeMillis() / 1000,
                        replyToMessageId = pendingReply?.messageId,
                    ),
                )
                reloadLocalHistory()
            } catch (failure: ChatPairingException) {
                errorMessage = failure.message ?: "Não foi possível enviar a mensagem"
                draft = text // give the user their draft back so it isn't silently lost
                replyTarget = pendingReply // same reasoning — nothing was sent, the reply-in-progress state shouldn't vanish either
            } finally {
                isSending = false
            }
        }
    }

    /** Plan §11 — the screen calls this when the user picks "Responder" from a message's action
     *  menu. Composing without picking anything else first leaves [replyTarget] null, same as
     *  today — this is purely additive. Named `startReplyTo`, not `setReplyTarget`, to avoid a
     *  JVM signature clash with the [replyTarget] property's own Compose-generated setter. */
    fun startReplyTo(message: MessageBubbleModel) {
        replyTarget = message
    }

    /** "Cancelar remove a referência, mantendo o texto digitado" (plan §11) — [draft] is
     *  untouched on purpose. */
    fun clearReplyTarget() {
        replyTarget = null
    }

    /** Plan §12 "Excluir do meu histórico" — [messageId] is exactly the composite key
     *  [toBubbleModel] already builds (`"${direction.name}-$sequence"`), so the screen can pass
     *  back what it was already given without this ViewModel needing a second identifier scheme.
     *  Local-only: no network call, no effect on the other side's copy, no change to session or
     *  ratchet state (see [ChatMessageHistoryStore.deleteOne]'s own doc on why no tombstone is
     *  needed here). Silently does nothing for a malformed or already-deleted id. */
    fun deleteMessage(messageId: String) {
        val id = conversationId ?: return
        val parts = messageId.split("-", limit = 2)
        if (parts.size != 2) return
        val direction = runCatching { ChatMessageDirection.valueOf(parts[0]) }.getOrNull() ?: return
        val sequence = parts[1].toLongOrNull() ?: return
        historyStore.deleteOne(id, direction, sequence)
        reloadLocalHistory()
    }

    /** Plan §13/§14 — opens/closes the search field; closing always clears [searchQuery], so
     *  reopening search never starts mid-filtered. */
    fun toggleSearch() {
        isSearching = !isSearching
        if (!isSearching) searchQuery = ""
    }

    /** Plan §13 "Excluir histórico: Remove conteúdo local, mantendo contato e estado técnico
     *  necessário" — distinct from [com.unyx.atlas.feature.connect.contact.ContactDetailViewModel.removeConnection],
     *  which also drops the pairing itself. Only the transcript goes away here; the ratchet
     *  session and contact record are untouched, so the conversation keeps working normally
     *  afterward. */
    fun clearHistory() {
        val id = conversationId ?: return
        historyStore.deleteAll(id)
        reloadLocalHistory()
    }

    /** Retries whatever is still queued for this conversation — a screen action (e.g. a banner's
     *  "tentar novamente"), not automatic; [startIncomingPoll] only ever fetches, it never
     *  resends on its own. */
    fun retryPending() {
        val id = conversationId ?: return
        viewModelScope.launch {
            runCatching { messagingRepository.resendPending(id) }
            reloadLocalHistory()
        }
    }

    /** [fetchAndDecryptNew] fetches for this DEVICE, not just the open conversation — it can
     *  return messages for other, closed conversations too (their sessions are still on this
     *  device, see [ChatConversationStore.listConversationIds]). Those still get appended to
     *  their own conversation's history (so they're not lost/unacked for nothing), just not
     *  reflected in this screen's visible [messages] unless they belong to [conversationId].
     *
     *  Reloads on every tick rather than only when a new text message arrived: an E2EE delivery
     *  receipt for THIS conversation's own sent messages (plan §12.3) never shows up in
     *  [fetchAndDecryptNew]'s return value — it only updates
     *  [ChatConversationStore.markDelivered] — so there is no cheaper signal to gate the reload on
     *  without the viewmodel knowing about receipts itself. A local prefs read every 4s while one
     *  conversation is open is the same trade-off [ChatMessageHistoryStore] already documents. */
    private fun startIncomingPoll() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                val incoming = runCatching { messagingRepository.fetchAndDecryptNew() }.getOrDefault(emptyList())
                incoming.forEach { decrypted ->
                    historyStore.append(
                        decrypted.conversationId,
                        ChatMessageRecord(
                            sequence = decrypted.sequence,
                            direction = ChatMessageDirection.RECEIVED,
                            text = decrypted.text,
                            timestampEpochSeconds = System.currentTimeMillis() / 1000,
                            // Already flipped into THIS device's own addressing by
                            // ChatMessagingRepository — see DecryptedIncoming's own doc.
                            replyToMessageId = decrypted.replyToMessageId,
                        ),
                    )
                }
                refreshContactState()
                reloadLocalHistory()
                delay(INCOMING_POLL_INTERVAL_MILLIS)
            }
        }
    }

    override fun onCleared() {
        pollingJob?.cancel()
    }
}
