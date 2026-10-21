package com.unyx.atlas.chatstorage

import android.content.SharedPreferences
import org.json.JSONObject

/** The other side of a confirmed pairing, as far as this device's own local records go —
 *  [conversationId] is the same id [ChatConversationStore] keys its session on, so the two
 *  stores are always looked up together: one gives "who is this conversation with", the other
 *  "what's the ratchet state and what's queued". Nothing in `core/chat-crypto`/`core/chat-pairing`
 *  persists this on its own — the pairing handshake knows [displayName] (it's carried in the
 *  invitation card) and [remoteUserId]/[remoteDeviceId] (from the claim/confirm exchange) only
 *  for the moment pairing completes; without this store, that information is gone the instant the
 *  pairing flow's own screen is closed. */
data class ChatContact(
    val conversationId: String,
    val displayName: String,
    val remoteUserId: String,
    val remoteDeviceId: String,
    val pairedAtEpochSeconds: Long,
    /** The presencial safety-number [com.unyx.atlas.chatpairing.PairingFingerprint] both sides
     *  compared and confirmed matched at pairing time — plan §16's "Identidade verificada: este
     *  aparelho conferiu a identidade criptográfica por pareamento". A historical record, not a
     *  live-recomputable value: the remote identity's raw public key is never persisted past
     *  session establishment (only HKDF-derived chain keys survive in [ChatConversationStore]), so
     *  this string IS the durable proof of what was actually verified, not merely a cosmetic echo
     *  of it. */
    val verifiedFingerprint: String,
    /** Plan §16's "Perfil recebido" — nome/recado the OTHER side self-reported via a
     *  [com.unyx.atlas.chatremote.ChatMessagePayload.ProfileUpdate], distinct from [displayName]
     *  above (which THIS device chose and nothing self-reported ever overwrites — see
     *  [displayName]'s own doc). Null until the first update actually arrives; plan §2 is explicit
     *  that a self-reported name/photo changing is not proof of anything cryptographic, so this
     *  must never be confused with [verifiedFingerprint]. */
    val receivedDisplayName: String? = null,
    val receivedStatusMessage: String? = null,
    /** Same "self-reported, not verified" caveat as [receivedDisplayName] — bio/interesses the
     *  other side chose to share via [com.unyx.atlas.chatremote.ChatMessagePayload.ProfileUpdate].
     *  Stored from the moment a `ProfileUpdate` arrives even though no screen renders them yet
     *  (deliberately deferred — see the card-UI work this shipped alongside). */
    val receivedBio: String? = null,
    val receivedInteresses: List<String> = emptyList(),
    /** Plan §13 "Bloquear: Interrompe novas interações e atualizações de perfil." Enforced in
     *  `ChatMessagingRepository` (both directions — refuses to [com.unyx.atlas.chatremote.ChatMessagingRepository.send]
     *  and skips a blocked conversation entirely in `fetchAndDecryptNew`), not here — this store
     *  only remembers the preference. */
    val isBlocked: Boolean = false,
    /** Plan §13/§15 "Silenciar notificações: Suprime alertas daquela conversa neste aparelho" —
     *  null = not silenced; otherwise the epoch second this silence expires (`Long.MAX_VALUE`
     *  models the plan's "até reativar" option without a separate tri-state field). "Silenciar
     *  não impede sincronização, não marca mensagens como lidas e não remove badges locais" — this
     *  field is read ONLY by [com.unyx.atlas.feature.connect.notifications]'s background worker
     *  before posting a notification; it has zero effect on `ChatMessagingRepository`'s own
     *  fetch/decrypt/receipt behavior, unlike [isBlocked]. */
    val mutedUntilEpochSeconds: Long? = null,
    /** User-configured star/favorite for this connection — kept alongside the contact record so
     *  "Conexões" can sort favorite pairs first without inventing a separate table. */
    val isFavorite: Boolean = false,
) {
    fun isMuted(nowEpochSeconds: Long): Boolean = mutedUntilEpochSeconds != null && nowEpochSeconds < mutedUntilEpochSeconds
}

/**
 * Durable local record of who each paired conversation is with — see [ChatContact]'s own doc for
 * why this has to exist separately from [ChatConversationStore]. [preferences] carries the same
 * sensitivity requirement as [ChatConversationStore]'s and [ChatIdentityStore]'s — a display name
 * isn't secret the way a chain key is, but which Atlas accounts a device has paired with is
 * exactly the kind of metadata that belongs behind the same Keystore-encrypted store, not a plain
 * one, so there's one access-control story for all of `chat:storage` instead of two.
 */
class ChatContactStore(private val preferences: SharedPreferences) {

    fun save(contact: ChatContact) {
        val committed = preferences.edit().putString(contactKey(contact.conversationId), contact.toJson().toString()).commit()
        check(committed) { "Could not persist chat contact for ${contact.conversationId}" }
    }

    fun load(conversationId: String): ChatContact? =
        preferences.getString(contactKey(conversationId), null)
            ?.let { runCatching { JSONObject(it).toChatContact(conversationId) }.getOrNull() }

    fun listAll(): List<ChatContact> {
        val prefix = "contact:"
        return preferences.all.entries
            .filter { (key, _) -> key.startsWith(prefix) }
            .mapNotNull { (key, value) ->
                val conversationId = key.removePrefix(prefix)
                (value as? String)?.let { runCatching { JSONObject(it).toChatContact(conversationId) }.getOrNull() }
            }
    }

    fun delete(conversationId: String) {
        preferences.edit().remove(contactKey(conversationId)).apply()
    }

    /** Applies a received [com.unyx.atlas.chatremote.ChatMessagePayload.ProfileUpdate] — never
     *  touches [ChatContact.displayName], only the separate `received*` fields (see their own
     *  doc). A no-op if there is no local contact record for [conversationId] at all — e.g. the
     *  connection was removed locally but a stale envelope still arrives; nothing to attach it to. */
    fun applyReceivedProfile(
        conversationId: String,
        displayName: String,
        statusMessage: String,
        bio: String = "",
        interesses: List<String> = emptyList(),
    ) {
        val existing = load(conversationId) ?: return
        save(
            existing.copy(
                receivedDisplayName = displayName,
                receivedStatusMessage = statusMessage,
                receivedBio = bio,
                receivedInteresses = interesses,
            ),
        )
    }

    /** Plan §13 "Bloquear"/unblock toggle — a no-op if there is no local contact record for
     *  [conversationId] (nothing to block). */
    fun setBlocked(conversationId: String, blocked: Boolean) {
        val existing = load(conversationId) ?: return
        save(existing.copy(isBlocked = blocked))
    }

    /** User favorite toggle for a connection — a plain local preference, not a server-side flag. */
    fun setFavorite(conversationId: String, favorite: Boolean) {
        val existing = load(conversationId) ?: return
        save(existing.copy(isFavorite = favorite))
    }

    /** Plan §15 — [untilEpochSeconds] null un-silences; `Long.MAX_VALUE` is "até reativar". A
     *  no-op if there is no local contact record for [conversationId]. */
    fun setMutedUntil(conversationId: String, untilEpochSeconds: Long?) {
        val existing = load(conversationId) ?: return
        save(existing.copy(mutedUntilEpochSeconds = untilEpochSeconds))
    }

    private fun contactKey(conversationId: String) = "contact:$conversationId"

    private fun ChatContact.toJson(): JSONObject {
        val json = JSONObject()
            .put("display_name", displayName)
            .put("remote_user_id", remoteUserId)
            .put("remote_device_id", remoteDeviceId)
            .put("paired_at", pairedAtEpochSeconds)
            .put("verified_fingerprint", verifiedFingerprint)
            .put("is_blocked", isBlocked)
            .put("is_favorite", isFavorite)
            receivedDisplayName?.let { json.put("received_display_name", it) }
        receivedStatusMessage?.let { json.put("received_status_message", it) }
        receivedBio?.let { json.put("received_bio", it) }
        if (receivedInteresses.isNotEmpty()) json.put("received_interesses", org.json.JSONArray(receivedInteresses))
        mutedUntilEpochSeconds?.let { json.put("muted_until", it) }
        return json
    }

    private fun JSONObject.toChatContact(conversationId: String): ChatContact = ChatContact(
        conversationId = conversationId,
        displayName = getString("display_name"),
        remoteUserId = getString("remote_user_id"),
        remoteDeviceId = getString("remote_device_id"),
        pairedAtEpochSeconds = getLong("paired_at"),
        verifiedFingerprint = optString("verified_fingerprint", ""),
        receivedDisplayName = if (has("received_display_name")) getString("received_display_name") else null,
        receivedStatusMessage = if (has("received_status_message")) getString("received_status_message") else null,
        receivedBio = if (has("received_bio")) getString("received_bio") else null,
        receivedInteresses = optJSONArray("received_interesses")?.let { array ->
            (0 until array.length()).map { array.getString(it) }
        }.orEmpty(),
        isBlocked = optBoolean("is_blocked", false),
        mutedUntilEpochSeconds = if (has("muted_until")) getLong("muted_until") else null,
        isFavorite = optBoolean("is_favorite", false),
    )
}
