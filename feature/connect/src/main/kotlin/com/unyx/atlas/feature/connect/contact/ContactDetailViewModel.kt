package com.unyx.atlas.feature.connect.contact

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.unyx.atlas.chatstorage.ChatAvatarStore
import com.unyx.atlas.chatstorage.ChatContact
import com.unyx.atlas.chatstorage.ChatContactStore
import com.unyx.atlas.chatstorage.ChatConversationStore
import com.unyx.atlas.chatstorage.ChatMessageHistoryStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Plan §16 "Detalhes do contato e segurança" — a narrower slice than the plan describes: no ID
 * Atlas, telefone or "situação da verificação" distinct from the fingerprint itself. What IS
 * real and shown here: the display name this device chose, when the pairing happened, the
 * presencial safety-number fingerprint that was actually compared and confirmed at pairing time
 * (plan: "Identidade verificada... conferiu a identidade criptográfica por pareamento" — never an
 * invented short code, see [com.unyx.atlas.chatpairing.PairingFingerprint]'s own doc), the
 * received profile (plan §5), and now the received avatar too (plan §4).
 */
@HiltViewModel
class ContactDetailViewModel @Inject constructor(
    private val contactStore: ChatContactStore,
    private val sessionStore: ChatConversationStore,
    private val historyStore: ChatMessageHistoryStore,
    private val avatarStore: ChatAvatarStore,
) : ViewModel() {

    private var conversationId: String? = null

    var contact by mutableStateOf<ChatContact?>(null)
        private set
    var avatarBytes by mutableStateOf<ByteArray?>(null)
        private set

    /** True once [removeConnection] has actually deleted everything — the screen's own signal to
     *  navigate back, since there is nothing left here to show afterward. */
    var removed by mutableStateOf(false)
        private set

    fun open(conversationId: String) {
        this.conversationId = conversationId
        contact = contactStore.load(conversationId)
        avatarBytes = avatarStore.loadForContact(conversationId)
    }

    /** Reloads from the store without needing [conversationId] passed again — a received profile
     *  or avatar update (plan §5/§4) can land in the background while this screen is already
     *  open, so a resume effect calls this to pick it up instead of requiring the user to
     *  navigate away and back. */
    fun refresh() {
        val id = conversationId ?: return
        contact = contactStore.load(id)
        avatarBytes = avatarStore.loadForContact(id)
    }

    /** Deletes the pairing, the ratchet session, the local message history, and the received
     *  avatar for this conversation — irreversible from this device's side (the plan has no
     *  "re-add without re-pairing" concept; the other side would need a fresh invite). The caller
     *  is responsible for confirming with the user before calling this — this function itself
     *  does not ask. */
    fun removeConnection() {
        val id = conversationId ?: return
        contactStore.delete(id)
        sessionStore.deleteConversation(id)
        historyStore.deleteAll(id)
        avatarStore.deleteForContact(id)
        contact = null
        avatarBytes = null
        removed = true
    }

    /** Toggles the local favorite flag for this connection without affecting the pairing itself. */
    fun toggleFavorite() {
        val id = conversationId ?: return
        val current = contactStore.load(id) ?: return
        contactStore.setFavorite(id, !current.isFavorite)
        contact = contactStore.load(id)
    }

    /** Plan §13 "Bloquear"/unblock — reversible, unlike [removeConnection]. Enforcement itself
     *  lives in `ChatMessagingRepository` (it's the layer that actually sends/receives); this just
     *  flips the stored preference and refreshes what the screen shows. */
    fun setBlocked(blocked: Boolean) {
        val id = conversationId ?: return
        contactStore.setBlocked(id, blocked)
        contact = contactStore.load(id)
    }
}
