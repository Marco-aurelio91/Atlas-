package com.unyx.atlas.chatremote

import java.util.concurrent.ConcurrentHashMap

/** A tiny in-memory stand-in for the real `atlas_chat_*` backend — just enough behavior to drive
 *  [ChatPairingRepository] through the full bilateral flow in a JVM test, including the one
 *  server-side invariant that actually matters to get right: a second claim on an already-claimed
 *  invite must fail (T-04), mirroring the real migration's atomic `UPDATE ... WHERE
 *  status='pending'`. Not a general PostgREST/RLS simulator — anything this repository doesn't
 *  exercise isn't modeled. */
class FakeChatPairingRemoteClient : ChatPairingRemoteClient {
    private data class Device(val identityPublicKeyB64: String)
    private data class Invite(
        var status: String,
        val tokenB64: String,
        val expiresAtEpochSeconds: Long,
        var claimedByUserId: String? = null,
        var claimedByDeviceId: String? = null,
    )

    private val devices = ConcurrentHashMap<String, Device>()
    private val invites = ConcurrentHashMap<String, Invite>()
    private val activeRelationshipsByInvitation = ConcurrentHashMap<String, Boolean>()
    // Mirrors the real schema's "exactly one active device per account": the server derives both
    // the caller (auth.uid()) and their active device from the session/account, never from a
    // client-supplied parameter — this fake models that by remembering the most recent device
    // registered under each access token (its stand-in for "account").
    private val activeDeviceIdByAccessToken = ConcurrentHashMap<String, String>()

    /** Not part of the interface — lets a test fast-forward past expiry without a real clock. */
    var nowEpochSeconds: Long = 0L

    override suspend fun registerDevice(deviceId: String, identityPublicKeyB64: String, deviceEpoch: Long, accessToken: String) {
        devices[deviceId] = Device(identityPublicKeyB64)
        activeDeviceIdByAccessToken[accessToken] = deviceId
    }

    override suspend fun createContactInvite(invitationId: String, tokenB64: String, expiresAtEpochSeconds: Long, accessToken: String) {
        check(!invites.containsKey(invitationId)) { "invite already exists" }
        invites[invitationId] = Invite(status = "pending", tokenB64 = tokenB64, expiresAtEpochSeconds = expiresAtEpochSeconds)
    }

    override suspend fun claimContactInvite(invitationId: String, tokenB64: String, accessToken: String) {
        val invite = invites[invitationId] ?: throw IllegalStateException("invite not found")
        val deviceId = activeDeviceIdByAccessToken[accessToken] ?: throw IllegalStateException("no active device registered")
        synchronized(invite) {
            check(invite.status == "pending") { "invite not claimable (already ${invite.status})" }
            check(invite.expiresAtEpochSeconds > nowEpochSeconds) { "invite expired" }
            check(invite.tokenB64 == tokenB64) { "wrong token" }
            invite.status = "claimed"
            // The fake's stand-in for a real userId: tests give each side's FakeChatAuthSession
            // the same string for both, so this is already the right value to compare against.
            invite.claimedByUserId = accessToken
            invite.claimedByDeviceId = deviceId
        }
    }

    override suspend fun confirmContactRelationship(invitationId: String, accessToken: String) {
        val invite = invites[invitationId] ?: throw IllegalStateException("invite not found")
        check(invite.status == "claimed") { "invite has not been claimed yet" }
        invite.status = "confirmed"
        activeRelationshipsByInvitation[invitationId] = true
    }

    override suspend fun fetchInviteClaim(invitationId: String, accessToken: String): RemoteInviteClaim? {
        val invite = invites[invitationId] ?: return null
        return RemoteInviteClaim(invite.status, invite.claimedByUserId, invite.claimedByDeviceId)
    }

    override suspend fun fetchDeviceIdentityPublicKeyB64(deviceId: String, accessToken: String): String? =
        devices[deviceId]?.identityPublicKeyB64

    override suspend fun isRelationshipActive(invitationId: String, accessToken: String): Boolean =
        activeRelationshipsByInvitation[invitationId] == true

    fun forceExpire(invitationId: String) {
        nowEpochSeconds = (invites[invitationId]?.expiresAtEpochSeconds ?: 0L) + 1
    }
}

class FakeChatAuthSession(private val userId: String, private val accessToken: String) : ChatAuthSession {
    override fun currentUserId(): String = userId
    override suspend fun currentAccessToken(): String = accessToken
}
