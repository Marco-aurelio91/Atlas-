package com.unyx.atlas.chatstorage

import android.content.SharedPreferences
import com.unyx.atlas.chatcrypto.ChatIdentityKeyPair
import org.json.JSONObject
import java.util.Base64

/**
 * Durable storage for this device's own chat identity key pair — see
 * `docs/ATLAS_CHAT_DESENHO_CRIPTOGRAFICO.md` §3: this is as sensitive as the account root key
 * (`core/security`'s `AtlasAccountKeyManager`) and must only ever be backed by a Keystore-encrypted
 * [SharedPreferences], never a plain one. Unlike [ChatConversationStore]'s per-conversation ratchet
 * state, losing this is not recoverable by resending — it forces every existing pairing to be
 * redone, so callers must open it over `AtlasSecurePreferences`, not a throwaway store.
 *
 * One identity per device (not per conversation), so there is nothing to key by — `load`/`save`
 * always refer to the single current identity.
 */
class ChatIdentityStore(private val preferences: SharedPreferences) {

    fun load(): ChatIdentityKeyPair? =
        preferences.getString(KEY_IDENTITY, null)?.let { stored ->
            runCatching {
                val json = JSONObject(stored)
                ChatIdentityKeyPair.restore(
                    privateKeyBytes = decode(json.getString(FIELD_PRIVATE_KEY)),
                    publicKeyBytes = decode(json.getString(FIELD_PUBLIC_KEY)),
                )
            }.getOrNull()
        }

    fun save(identity: ChatIdentityKeyPair) {
        val json = JSONObject()
            .put(FIELD_PRIVATE_KEY, encode(identity.privateKeyBytes()))
            .put(FIELD_PUBLIC_KEY, encode(identity.publicKeyBytes))
        val committed = preferences.edit().putString(KEY_IDENTITY, json.toString()).commit()
        check(committed) { "Could not persist the chat identity key pair" }
    }

    /** The identity already stored, or a freshly generated one persisted on the spot — the usual
     *  "first run creates it" path so every caller doesn't have to repeat that check themselves. */
    fun loadOrCreate(): ChatIdentityKeyPair = load() ?: ChatIdentityKeyPair.generate().also { save(it) }

    private companion object {
        const val KEY_IDENTITY = "chat_identity"
        const val FIELD_PRIVATE_KEY = "private_key_b64"
        const val FIELD_PUBLIC_KEY = "public_key_b64"

        fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
        fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)
    }
}
