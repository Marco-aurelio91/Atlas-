package com.unyx.atlas.feature.connect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.unyx.atlas.chatstorage.ChatAvatarStore
import com.unyx.atlas.chatstorage.ChatContact
import com.unyx.atlas.chatstorage.ChatContactStore
import com.unyx.atlas.chatstorage.ChatConversationStore
import com.unyx.atlas.chatstorage.ChatMessageHistoryStore
import com.unyx.atlas.feature.connect.model.ConversationRowModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Builds "Conexões"'s list from what's actually stored locally — [ChatContactStore] for who
 *  each conversation is with, [ChatMessageHistoryStore] for the last message (if any) to preview
 *  and to order by. No unread-count tracking exists yet, so that field is always 0 — an honest
 *  zero, not a fabricated number, until something actually tracks read state. */
@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    private val contactStore: ChatContactStore,
    private val sessionStore: ChatConversationStore,
    private val historyStore: ChatMessageHistoryStore,
    private val avatarStore: ChatAvatarStore,
) : ViewModel() {

    var connections by mutableStateOf<List<ConversationRowModel>>(emptyList())
        private set

    init {
        refresh()
    }

    /** Local-only, no network — safe to call every time the screen resumes to pick up messages a
     *  background fetch loop may have added since the list was last built. */
    fun refresh() {
        connections = contactStore.listAll()
            .sortedWith(compareByDescending<ChatContact> { it.isFavorite }.thenByDescending { contact ->
                historyStore.lastMessage(contact.conversationId)?.timestampEpochSeconds ?: contact.pairedAtEpochSeconds
            })
            .map { contact ->
                val last = historyStore.lastMessage(contact.conversationId)
                ConversationRowModel(
                    conversationId = contact.conversationId,
                    displayName = contact.displayName,
                    photoUri = null,
                    photoBytes = avatarStore.loadForContact(contact.conversationId),
                    isFavorite = contact.isFavorite,
                    lastEventTimeLabel = formatTimeLabel(last?.timestampEpochSeconds ?: contact.pairedAtEpochSeconds),
                    // Plan §9: "Não mostrar texto da mensagem por padrão" — a generic label, same
                    // as ConversationRow's own preview examples, never the actual plaintext.
                    previewLabel = if (last == null) "Nenhuma mensagem ainda" else "Conversa privada",
                    unreadCount = 0,
                )
            }
    }

    fun toggleFavorite(conversationId: String) {
        val contact = contactStore.load(conversationId) ?: return
        contactStore.setFavorite(conversationId, !contact.isFavorite)
        refresh()
    }

    fun deleteConnection(conversationId: String) {
        contactStore.delete(conversationId)
        sessionStore.deleteConversation(conversationId)
        historyStore.deleteAll(conversationId)
        avatarStore.deleteForContact(conversationId)
        refresh()
    }

    private fun formatTimeLabel(epochSeconds: Long): String {
        val instant = Instant.ofEpochSecond(epochSeconds)
        val zone = ZoneId.systemDefault()
        val today = Instant.now().atZone(zone).toLocalDate()
        val date = instant.atZone(zone).toLocalDate()
        return if (date == today) {
            DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(instant)
        } else {
            DateTimeFormatter.ofPattern("dd/MM").withZone(zone).format(instant)
        }
    }
}
