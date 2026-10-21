package com.unyx.atlas.chatstorage

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** This device's own local chat profile — plan §2/§3 ("Meu perfil no chat"), scoped to what
 *  actually exists today: these fields this device can set and read back without any
 *  distribution infrastructure of its own (distribution itself lives in `core:chat-remote`'s
 *  `ChatMessagePayload.ProfileUpdate`). No ID Atlas, telefone or versão/geração here — those
 *  either need pieces that aren't built yet or belong to a different layer entirely (ID Atlas is
 *  the signed-in Supabase user id, not something this store should duplicate). [displayName] is
 *  what [ChatPairingRepository] prefills a new invite's card with. [statusMessage] ("recado"),
 *  [bio] and [interesses] are all distributed to paired contacts over E2EE control messages (see
 *  [ChatMessagePayload.ProfileUpdate]) — none of them ever travel inside a pairing invitation
 *  card itself (QR/NFC only ever carries pairing/crypto metadata, never profile content). */
data class ChatProfile(
    val displayName: String,
    val statusMessage: String,
    val bio: String = "",
    val interesses: List<String> = emptyList(),
)

/** Durable local storage for [ChatProfile] — one JSON blob under one key, same trade-off
 *  [ChatMessageHistoryStore] already documents for something this small. [preferences] carries
 *  the same Keystore-encrypted requirement as the rest of `chat:storage`: a display name and
 *  recado aren't ratchet secrets, but they're still this account's own chat identity metadata,
 *  not public data, so there's one access-control story for the whole module instead of two. */
class ChatProfileStore(private val preferences: SharedPreferences) {

    fun load(): ChatProfile? =
        preferences.getString(KEY, null)?.let { runCatching { JSONObject(it).toProfile() }.getOrNull() }

    fun save(profile: ChatProfile) {
        val json = JSONObject()
            .put("display_name", profile.displayName)
            .put("status_message", profile.statusMessage)
            .put("bio", profile.bio)
            .put("interesses", JSONArray(profile.interesses))
        val committed = preferences.edit().putString(KEY, json.toString()).commit()
        check(committed) { "Could not persist the local chat profile" }
    }

    private fun JSONObject.toProfile(): ChatProfile = ChatProfile(
        displayName = optString("display_name", ""),
        statusMessage = optString("status_message", ""),
        bio = optString("bio", ""),
        interesses = optJSONArray("interesses")?.let { array ->
            (0 until array.length()).map { array.getString(it) }
        }.orEmpty(),
    )

    private companion object {
        const val KEY = "profile"
    }
}
