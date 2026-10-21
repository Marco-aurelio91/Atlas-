package com.unyx.atlas.chatremote

import com.unyx.atlas.chatcrypto.ChatEnvelope
import com.unyx.atlas.chatcrypto.ChatSession
import com.unyx.atlas.chatcrypto.DecryptResult
import com.unyx.atlas.chatstorage.ChatAvatarStore
import com.unyx.atlas.chatstorage.ChatContactStore
import com.unyx.atlas.chatstorage.ChatConversationStore
import com.unyx.atlas.chatstorage.ChatMessageDirection
import java.util.Base64

/** A text message decrypted off the wire, ready to show — [envelopeId] is only needed
 *  internally (to ack it, already done by the time the caller sees this); [conversationId] and
 *  [sequence] are what a caller needs to record this in a local transcript without re-deriving
 *  them, since [fetchAndDecryptNew] can return messages spanning several conversations in one
 *  batch. An incoming [ChatMessagePayload.DeliveryReceipt] never becomes one of these — it
 *  updates [ChatConversationStore.markDelivered] instead and is otherwise invisible to callers,
 *  the same way the plan's §12.3 describes it as internal to the sync layer.
 *
 *  [replyToMessageId], when present, is ALREADY in the LOCAL (receiver's) addressing scheme —
 *  [fetchAndDecryptNew] flips it from the sender's scheme before this is ever constructed, so a
 *  caller can look it up in its own [com.unyx.atlas.chatstorage.ChatMessageHistoryStore] exactly
 *  like any `"${direction}-${sequence}"` id it already produces itself. Null if the sender's
 *  reference didn't parse — treated as "not a reply" rather than failing the whole message. */
data class DecryptedIncoming(
    val envelopeId: String,
    val conversationId: String,
    val senderUserId: String,
    val sequence: Long,
    val text: String,
    val replyToMessageId: String? = null,
)

private const val ENVELOPE_VERSION = 1

/**
 * `chat:sync` — the layer that actually moves a [ChatEnvelope] between two paired devices, on
 * top of the session [ChatPairingRepository] already established and persisted. Deliberately
 * separate from [ChatPairingRepository]: pairing and messaging are different concerns with
 * different failure modes (a pairing failure means "not connected yet"; a send failure means
 * "connected, but this one message didn't go through — retry it").
 *
 * Implements the plan's §12.3 E2EE delivery receipt: a receipt is just another
 * [ChatMessagePayload] on the SAME ratchet/transport as a text message, not a second channel.
 * [fetchAndDecryptNew] sends one automatically after successfully decrypting+persisting a text
 * message, and a receipt never triggers another receipt — exactly "Recibos não geram outros
 * recibos E2EE, evitando ciclos."
 */
class ChatMessagingRepository(
    private val sessionStore: ChatConversationStore,
    private val contactStore: ChatContactStore,
    private val avatarStore: ChatAvatarStore,
    private val remote: ChatMessageRemoteClient,
    private val auth: ChatAuthSession,
) {
    private suspend fun requireAccessToken(): String =
        auth.currentAccessToken() ?: throw ChatPairingException("Not signed in")

    /** Encrypts [text] as a [ChatMessagePayload.Text] with the already-established session for
     *  [conversationId] and sends it — see [send]'s own doc for the local-always/network-best-
     *  effort contract this follows. [replyToMessageId], when given, must already be in THIS
     *  device's own `"${direction}-${sequence}"` addressing — see [ChatMessagePayload.Text]'s own
     *  doc on why the far side has to flip it, not this method. */
    suspend fun sendText(conversationId: String, text: String, replyToMessageId: String? = null): ChatEnvelope =
        send(conversationId, ChatMessagePayload.Text(text, replyToMessageId))

    /** Plan §5 — sends this device's own name/recado/bio/interesses to an already-paired contact
     *  as a control message, never shown in that conversation's timeline (see
     *  [ChatMessagePayload.ProfileUpdate]'s own doc). The caller (`ProfileViewModel`, or
     *  `ConnectPairingViewModel` right after a fresh pairing establishes) is responsible for
     *  calling this once per paired contact; this method itself only knows how to address one. */
    suspend fun sendProfileUpdate(
        conversationId: String,
        displayName: String,
        statusMessage: String,
        bio: String = "",
        interesses: List<String> = emptyList(),
    ): ChatEnvelope =
        send(conversationId, ChatMessagePayload.ProfileUpdate(displayName, statusMessage, bio, interesses))

    /** Plan §4.3/§5.2 — sends this device's own processed avatar (or `null` to signal "removed")
     *  to an already-paired contact, over the same channel as [sendProfileUpdate]. The caller
     *  (`ProfileViewModel`) decides when: immediately after processing a newly picked photo, or
     *  immediately after the user removes their avatar. */
    suspend fun sendAvatarUpdate(conversationId: String, avatarBytes: ByteArray?): ChatEnvelope =
        send(conversationId, ChatMessagePayload.AvatarUpdate(avatarBytes))

    /** Encrypts [payload] with the already-established session for [conversationId] and persists
     *  it locally (durable before any network call — T-10, same ordering
     *  [ChatConversationStore.persistAfterEncrypt] already enforces) — this local half always
     *  happens and is what's returned. The network send that follows is best-effort: a failure
     *  there is swallowed, not thrown, leaving the envelope in [ChatConversationStore.pendingOutbox]
     *  for [resendPending] to retry later (never re-encrypted — that would advance the ratchet and
     *  desync the two sides). This is deliberate, not an oversight: a caller needs the envelope
     *  (specifically its `sequence`) back regardless of whether the network leg succeeded, to
     *  record the message in a local transcript immediately — a UI that only finds out what got
     *  encrypted when the network happens to cooperate would lose track of the user's own sent
     *  message the moment connectivity hiccups. [sessionStore.pendingOutbox] is still the source
     *  of truth for "did the server actually get this" (present = no; absent = yes). Only a
     *  missing credential ([requireAccessToken]'s "not signed in") or no session at all for
     *  [conversationId] still throws — both mean nothing could even be encrypted. A blocked
     *  contact (plan §13: "Interrompe novas interações e atualizações de perfil") also throws,
     *  covering [sendText] and [sendProfileUpdate] alike since both funnel through here — nothing
     *  gets encrypted or persisted for a blocked conversation at all, not even locally. */
    suspend fun send(conversationId: String, payload: ChatMessagePayload): ChatEnvelope {
        if (contactStore.load(conversationId)?.isBlocked == true) {
            throw ChatPairingException("Esta conexão está bloqueada")
        }
        val snapshot = sessionStore.loadSession(conversationId)
            ?: throw ChatPairingException("No established session for $conversationId")
        val session = ChatSession.restore(snapshot)
        return encryptPersistAndDispatch(conversationId, session, payload)
    }

    private suspend fun encryptPersistAndDispatch(
        conversationId: String,
        session: ChatSession,
        payload: ChatMessagePayload,
    ): ChatEnvelope {
        val envelope = session.encrypt(ChatMessagePayload.encode(payload))
        sessionStore.persistAfterEncrypt(session.snapshot(), envelope)

        val token = requireAccessToken()
        val sent = runCatching {
            remote.sendEnvelope(
                conversationId = envelope.conversationId,
                sequence = envelope.sequence,
                nonceB64 = Base64.getEncoder().encodeToString(envelope.nonce),
                ciphertextB64 = Base64.getEncoder().encodeToString(envelope.ciphertextAndTag),
                accessToken = token,
            )
        }
        if (sent.isSuccess) sessionStore.markSent(conversationId, envelope.sequence)
        return envelope
    }

    /** Resends whatever is still in [ChatConversationStore.pendingOutbox] for [conversationId] —
     *  the exact same envelopes [send] already persisted, never re-encrypted. Safe to call
     *  repeatedly: the server's own `atlas_chat_send_envelope` is idempotent per
     *  (conversation, sender device, sequence). */
    suspend fun resendPending(conversationId: String) {
        val token = requireAccessToken()
        sessionStore.pendingOutbox(conversationId).forEach { envelope ->
            val sent = runCatching {
                remote.sendEnvelope(
                    conversationId = envelope.conversationId,
                    sequence = envelope.sequence,
                    nonceB64 = Base64.getEncoder().encodeToString(envelope.nonce),
                    ciphertextB64 = Base64.getEncoder().encodeToString(envelope.ciphertextAndTag),
                    accessToken = token,
                )
            }
            if (sent.isSuccess) sessionStore.markSent(conversationId, envelope.sequence)
        }
    }

    /** Fetches whatever is pending for THIS device across all conversations, decrypts each with
     *  its matching local session, persists the receiving-side state before acking (T-11 — same
     *  ordering [ChatConversationStore.persistAfterReceive] already enforces), then acks. A
     *  [DecryptResult.Rejected] envelope (tampered, or for a session that no longer matches) is
     *  still acked — plan §12.1: "erros de identidade... não entram em loop de retry" — leaving a
     *  permanently-undecryptable envelope in the queue forever helps no one. Returns only text
     *  messages that newly decrypted; a [DecryptResult.Duplicate] is acked and silently dropped,
     *  not returned a second time, and a decrypted [ChatMessagePayload.DeliveryReceipt] is never
     *  returned at all (see [DecryptedIncoming]'s own doc) — it only marks
     *  [ChatConversationStore.markDelivered] for the sequence it references. */
    suspend fun fetchAndDecryptNew(): List<DecryptedIncoming> {
        val token = requireAccessToken()
        val remoteEnvelopes = remote.fetchPendingEnvelopes(token)
        val delivered = mutableListOf<DecryptedIncoming>()

        // Grouped so a conversation's session is restored/persisted once per batch, not once per
        // envelope — mirrors the batch-unwrap performance fix already applied elsewhere in this
        // codebase (AtlasAccountKeyManager.decryptObjects) rather than repeating the same mistake.
        val byConversation = remoteEnvelopes.groupBy { it.conversationId }
        for ((conversationId, envelopesForConversation) in byConversation) {
            // Plan §13: a blocked contact's envelopes are left entirely alone — not decrypted,
            // not acked, not shown, not counted as a delivery or profile update. If the contact
            // is ever unblocked, normal processing picks them up exactly where it left off.
            if (contactStore.load(conversationId)?.isBlocked == true) continue
            val snapshot = sessionStore.loadSession(conversationId) ?: continue // no local session: can't decrypt, can't ack either
            val session = ChatSession.restore(snapshot) // decrypt()/encrypt() mutate this in place across the loop below, same as ChatSession's own tests rely on
            for (remoteEnvelope in envelopesForConversation.sortedBy { it.sequence }) {
                val envelope = ChatEnvelope(
                    version = ENVELOPE_VERSION,
                    conversationId = remoteEnvelope.conversationId,
                    senderDeviceId = remoteEnvelope.senderDeviceId,
                    sequence = remoteEnvelope.sequence,
                    nonce = Base64.getDecoder().decode(remoteEnvelope.nonceB64),
                    ciphertextAndTag = Base64.getDecoder().decode(remoteEnvelope.ciphertextB64),
                )
                val result = runCatching { session.decrypt(envelope) }
                // T-11: the receiving-side ratchet state is durable BEFORE a receipt is even
                // attempted — a crash here means a redelivery reads as Duplicate on restart
                // instead of showing (or re-acking) the message twice.
                sessionStore.persistAfterReceive(session.snapshot())
                when (val decrypted = result.getOrNull()) {
                    is DecryptResult.Delivered -> {
                        when (val payload = ChatMessagePayload.decode(decrypted.plaintext)) {
                            is ChatMessagePayload.Text -> {
                                delivered += DecryptedIncoming(
                                    envelopeId = remoteEnvelope.id,
                                    conversationId = remoteEnvelope.conversationId,
                                    senderUserId = remoteEnvelope.senderUserId,
                                    sequence = remoteEnvelope.sequence,
                                    text = payload.text,
                                    replyToMessageId = payload.replyToMessageId?.let { flipMessageIdDirection(it) },
                                )
                                // Plan §12.3: produce the E2EE receipt referencing this message,
                                // through the SAME session object already advanced above — never a
                                // fresh restore, so this reuses the receiving-chain state
                                // persistAfterReceive just made durable instead of racing it.
                                runCatching {
                                    encryptPersistAndDispatch(
                                        conversationId, session,
                                        ChatMessagePayload.DeliveryReceipt(remoteEnvelope.sequence),
                                    )
                                }
                            }
                            is ChatMessagePayload.DeliveryReceipt -> {
                                // No receipt-of-receipt — "Recibos não geram outros recibos E2EE."
                                sessionStore.markDelivered(conversationId, payload.forSequence)
                            }
                            is ChatMessagePayload.ProfileUpdate -> {
                                // Plan §5.1: "Não são mensagens visíveis na linha do tempo" — never
                                // added to [delivered], and no E2EE receipt either (receipts prove
                                // a TEXT message was seen; nothing in the UI needs to claim a
                                // control update was "Entregue").
                                contactStore.applyReceivedProfile(
                                    conversationId, payload.displayName, payload.statusMessage, payload.bio, payload.interesses,
                                )
                            }
                            is ChatMessagePayload.AvatarUpdate -> {
                                // Same non-timeline treatment as ProfileUpdate. Null bytes = the
                                // other side removed their avatar (plan §4.3) — delete, don't keep
                                // showing a stale photo.
                                val bytes = payload.avatarBytes
                                if (bytes == null) avatarStore.deleteForContact(conversationId) else avatarStore.saveForContact(conversationId, bytes)
                            }
                            null -> Unit // decrypted fine but the framing itself is malformed; nothing to show or act on.
                        }
                        runCatching { remote.ackEnvelope(remoteEnvelope.id, token) }
                    }
                    DecryptResult.Duplicate -> runCatching { remote.ackEnvelope(remoteEnvelope.id, token) }
                    is DecryptResult.Rejected -> runCatching { remote.ackEnvelope(remoteEnvelope.id, token) }
                    null -> Unit // the sender device id didn't match this session at all (IllegalArgumentException) — leave it for inspection, don't ack blindly.
                }
            }
        }
        return delivered
    }

    /** The seam where a reply reference crosses from the SENDER's local addressing to the
     *  RECEIVER's — see [ChatMessagePayload.Text]'s own doc for why a flip is necessary at all
     *  (the sending/receiving chains that make up one conversation are the same physical sequence
     *  space viewed from opposite ends, so what the sender calls `SENT` the receiver has always
     *  called `RECEIVED`, for that exact same message, and vice versa). Null — "not a reply this
     *  device can resolve" — for anything that doesn't parse as `"<direction>-<sequence>"`,
     *  handled by the caller as if no reply reference were sent at all. */
    private fun flipMessageIdDirection(messageId: String): String? {
        val parts = messageId.split("-", limit = 2)
        if (parts.size != 2) return null
        val direction = runCatching { ChatMessageDirection.valueOf(parts[0]) }.getOrNull() ?: return null
        val flipped = if (direction == ChatMessageDirection.SENT) ChatMessageDirection.RECEIVED else ChatMessageDirection.SENT
        return "${flipped.name}-${parts[1]}"
    }
}
