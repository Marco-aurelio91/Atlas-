package com.unyx.atlas.chatremote

import com.unyx.atlas.chatstorage.ChatAvatarStore

/** In-memory stand-in for [ChatAvatarStore] — the real implementation is backed by
 *  [androidx.security.crypto.EncryptedFile], which needs a real Android Keystore and has no JVM
 *  equivalent, the same reason [FakeChatMessageRemoteClient] exists instead of the real Supabase
 *  client in these tests. */
class FakeChatAvatarStore : ChatAvatarStore {
    private var own: ByteArray? = null
    private val contacts = mutableMapOf<String, ByteArray>()

    override fun saveOwn(bytes: ByteArray) {
        own = bytes
    }

    override fun loadOwn(): ByteArray? = own

    override fun deleteOwn() {
        own = null
    }

    override fun saveForContact(conversationId: String, bytes: ByteArray) {
        contacts[conversationId] = bytes
    }

    override fun loadForContact(conversationId: String): ByteArray? = contacts[conversationId]

    override fun deleteForContact(conversationId: String) {
        contacts.remove(conversationId)
    }
}
