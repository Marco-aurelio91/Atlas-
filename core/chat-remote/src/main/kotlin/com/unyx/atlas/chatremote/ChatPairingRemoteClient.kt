package com.unyx.atlas.chatremote

import com.unyx.atlas.memory.SupabaseRestClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant

/** What the server answered for `atlas_chat_contact_invites` row [invitationId] — whether, and by
 *  whom, it has been claimed (plan §8.3 step 3). Null fields mean "not claimed yet", not missing
 *  data: a 'pending' invite genuinely has no claimant. */
data class RemoteInviteClaim(
    val status: String,
    val claimedByUserId: String?,
    val claimedByDeviceId: String?,
)

/** The `atlas_chat_*` RPC/SELECT surface from
 * `supabase/migrations/20261016190000_atlas_chat_pairing.sql` — see
 * `docs/ATLAS_CHAT_DESENHO_CRIPTOGRAFICO.md` §9.1 for what each call is modeling and what it is
 * NOT (no message transport here, only device registration and the claim/confirm handshake).
 * [accessToken] is always the caller's own — every RPC derives the acting identity from
 * `auth.uid()` server-side, never from a parameter, so there's nothing to spoof by passing someone
 * else's id here even by mistake. */
interface ChatPairingRemoteClient {
    suspend fun registerDevice(deviceId: String, identityPublicKeyB64: String, deviceEpoch: Long, accessToken: String)
    suspend fun createContactInvite(invitationId: String, tokenB64: String, expiresAtEpochSeconds: Long, accessToken: String)
    suspend fun claimContactInvite(invitationId: String, tokenB64: String, accessToken: String)
    suspend fun confirmContactRelationship(invitationId: String, accessToken: String)

    /** Null if the invite doesn't exist (yet, from this caller's RLS-visible perspective) — not
     *  the same as "exists but still pending", which comes back with a non-null [RemoteInviteClaim]
     *  whose `claimedByUserId` is null. */
    suspend fun fetchInviteClaim(invitationId: String, accessToken: String): RemoteInviteClaim?

    /** Null if [deviceId] isn't visible to the caller under RLS — e.g. asked before anyone claimed
     *  or confirmed anything involving that device (see the migration's discovery policy). */
    suspend fun fetchDeviceIdentityPublicKeyB64(deviceId: String, accessToken: String): String?

    /** True once `atlas_chat_confirm_contact_relationship` has run for this invitation and the
     *  resulting relationship is `status = 'active'`. */
    suspend fun isRelationshipActive(invitationId: String, accessToken: String): Boolean
}

class SupabaseChatPairingRemoteClient(
    private val supabase: SupabaseRestClient,
) : ChatPairingRemoteClient {

    override suspend fun registerDevice(
        deviceId: String,
        identityPublicKeyB64: String,
        deviceEpoch: Long,
        accessToken: String,
    ) = withContext(Dispatchers.IO) {
        supabase.rpc(
            "atlas_chat_register_device",
            JSONObject()
                .put("p_device_id", deviceId)
                .put("p_identity_public_key_b64", identityPublicKeyB64)
                .put("p_device_epoch", deviceEpoch),
            accessToken,
        )
        Unit
    }

    override suspend fun createContactInvite(
        invitationId: String,
        tokenB64: String,
        expiresAtEpochSeconds: Long,
        accessToken: String,
    ) = withContext(Dispatchers.IO) {
        supabase.rpc(
            "atlas_chat_create_contact_invite",
            JSONObject()
                .put("p_invitation_id", invitationId)
                .put("p_token", tokenB64)
                .put("p_expires_at", Instant.ofEpochSecond(expiresAtEpochSeconds).toString()),
            accessToken,
        )
        Unit
    }

    override suspend fun claimContactInvite(
        invitationId: String,
        tokenB64: String,
        accessToken: String,
    ) = withContext(Dispatchers.IO) {
        supabase.rpc(
            "atlas_chat_claim_contact_invite",
            JSONObject().put("p_invitation_id", invitationId).put("p_token", tokenB64),
            accessToken,
        )
        Unit
    }

    override suspend fun confirmContactRelationship(invitationId: String, accessToken: String) = withContext(Dispatchers.IO) {
        supabase.rpc(
            "atlas_chat_confirm_contact_relationship",
            JSONObject().put("p_invitation_id", invitationId),
            accessToken,
        )
        Unit
    }

    override suspend fun fetchInviteClaim(invitationId: String, accessToken: String): RemoteInviteClaim? =
        withContext(Dispatchers.IO) {
            val rows = supabase.select(
                "atlas_chat_contact_invites",
                "invitation_id=eq.$invitationId&select=status,claimed_by_user_id,claimed_by_device_id",
                accessToken,
            )
            if (rows.length() == 0) return@withContext null
            val row = rows.getJSONObject(0)
            RemoteInviteClaim(
                status = row.getString("status"),
                claimedByUserId = row.stringOrNull("claimed_by_user_id"),
                claimedByDeviceId = row.stringOrNull("claimed_by_device_id"),
            )
        }

    override suspend fun fetchDeviceIdentityPublicKeyB64(deviceId: String, accessToken: String): String? =
        withContext(Dispatchers.IO) {
            val rows = supabase.select(
                "atlas_chat_devices",
                "device_id=eq.$deviceId&select=identity_public_key_b64",
                accessToken,
            )
            if (rows.length() == 0) return@withContext null
            rows.getJSONObject(0).getString("identity_public_key_b64")
        }

    override suspend fun isRelationshipActive(invitationId: String, accessToken: String): Boolean =
        withContext(Dispatchers.IO) {
            val rows = supabase.select(
                "atlas_chat_contact_relationships",
                "invitation_id=eq.$invitationId&select=status",
                accessToken,
            )
            rows.length() > 0 && rows.getJSONObject(0).getString("status") == "active"
        }
}

/** `JSONObject.optString` returns the literal text `"null"` for a JSON null, not Kotlin `null` —
 *  explicit about the distinction here since `claimed_by_user_id`/`claimed_by_device_id` are
 *  genuinely nullable columns (a 'pending' invite has no claimant yet, not an empty string). */
private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
