package com.unyx.atlas.chatremote

import com.unyx.atlas.chatcrypto.ChatIdentityKeyPair
import com.unyx.atlas.chatcrypto.ChatSession
import com.unyx.atlas.chatpairing.PairingFingerprint
import com.unyx.atlas.chatpairing.PairingInvitation
import com.unyx.atlas.chatstorage.ChatContact
import com.unyx.atlas.chatstorage.ChatContactStore
import com.unyx.atlas.chatstorage.ChatConversationStore
import com.unyx.atlas.chatstorage.ChatIdentityStore
import java.security.MessageDigest
import java.util.Base64

/** The one sliver of `core/memory`'s `AuthRepository` this module actually needs — a seam so
 *  [ChatPairingRepository] can be unit tested without constructing the real `AuthRepository`
 *  (which pulls in OkHttp and an Android `Context` through its own dependencies). The real
 *  wiring (`di/ChatRemoteModule.kt`) adapts the real `AuthRepository` to this. */
interface ChatAuthSession {
    fun currentUserId(): String?
    suspend fun currentAccessToken(): String?
}

/** Thrown for anything this repository can't proceed from — not signed in, device not registered
 *  yet, or the server rejected an RPC (an expired/already-claimed invite, a mismatched
 *  `invitation_id`, etc. — see the migration's own exception messages, carried through in
 *  [cause]). One type, like [com.unyx.atlas.chatpairing.InvalidPairingCardException]: callers only
 *  need to know pairing didn't proceed safely, not which of several server-side checks caught it. */
class ChatPairingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What A's side learns once B has claimed the invite (plan §8.3 step 3-5) — enough to compute
 *  the presencial fingerprint and, once the user confirms the comparison, call
 *  [ChatPairingRepository.confirmAndEstablish]. */
data class ClaimedInvite(
    val remoteUserId: String,
    val remoteDeviceId: String,
    val remotePublicKeyBytes: ByteArray,
    val fingerprint: String,
)

/** What B's side gets right after claiming (plan §8.3 step 3) — B already has A's public key
 *  directly from the scanned/tapped card, so computing the fingerprint here needs no extra
 *  server round trip, unlike A's side which only learns B's key after the claim. */
data class AcceptedInvite(
    val invitation: PairingInvitation,
    val fingerprint: String,
)

/**
 * Orchestrates `core/chat-crypto` + `core/chat-storage` + `core/chat-pairing` + the
 * `atlas_chat_*` backend (`core/chat-remote`'s own [ChatPairingRemoteClient]) into the handshake
 * from `docs/ATLAS_CHAT_Plano_Completo_Criacao_e_Implantacao.md` §8.3 — this is the layer a
 * pairing screen would actually call; none of those four pieces talk to each other on their own.
 *
 * Deliberately stops at "both sides have an established, persisted [ChatSession]" — sending a
 * real message over the network is `chat:sync`'s job (not built: see
 * `docs/ATLAS_CHAT_DESENHO_CRIPTOGRAFICO.md` §9.1/§9.2), not this repository's.
 *
 * Polling is the caller's concern, not this class's: [fetchClaim] and [waitForConfirmation] each
 * do exactly one read and return the current state (or null/false) — a screen calls them on a
 * timer or a pull-to-refresh, this repository doesn't loop or sleep internally.
 */
class ChatPairingRepository(
    private val identityStore: ChatIdentityStore,
    private val sessionStore: ChatConversationStore,
    private val contactStore: ChatContactStore,
    private val remote: ChatPairingRemoteClient,
    private val auth: ChatAuthSession,
) {
    /** The device id used throughout the chat stack (`ChatSession.establish`'s `localDeviceId`,
     *  the pairing card's `device_id`, the server's `atlas_chat_devices.device_id`) — derived from
     *  the identity's own public key rather than stored separately: it's already persisted for
     *  the life of that identity via [ChatIdentityStore], and the two share the exact same
     *  lifecycle (a new identity *is* a new device, per
     *  `docs/ATLAS_CHAT_DESENHO_CRIPTOGRAFICO.md` §3), so a second piece of storage for this would
     *  only be able to drift from the identity it's supposed to describe. */
    fun deviceIdFor(identity: ChatIdentityKeyPair): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.publicKeyBytes)
        return "device-" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest).take(32)
    }

    private fun requireUserId(): String = auth.currentUserId() ?: throw ChatPairingException("Not signed in")

    private suspend fun requireAccessToken(): String =
        auth.currentAccessToken() ?: throw ChatPairingException("Not signed in")

    private fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000

    /** First-run (or any-run, it's a no-op after the first) registration: loads or creates the
     *  local identity, then makes sure the server has its current public key on file under this
     *  device's id. Every other method in this class assumes this already ran. */
    suspend fun ensureDeviceRegistered(deviceEpoch: Long = 1L): ChatIdentityKeyPair {
        val identity = identityStore.loadOrCreate()
        val token = requireAccessToken()
        val deviceId = deviceIdFor(identity)
        try {
            remote.registerDevice(
                deviceId = deviceId,
                identityPublicKeyB64 = Base64.getEncoder().encodeToString(identity.publicKeyBytes),
                deviceEpoch = deviceEpoch,
                accessToken = token,
            )
        } catch (failure: Exception) {
            throw ChatPairingException("Could not register this device", failure)
        }
        return identity
    }

    /** A's side, plan §8.3 step 1: builds the invite card to present over NFC/QR, and registers
     *  it with the server (hash only — see the migration's own doc comment) so B's later claim
     *  has something to match against. */
    suspend fun createInvite(displayName: String, nowEpochSeconds: Long): PairingInvitation {
        val identity = ensureDeviceRegistered()
        val userId = requireUserId()
        val token = requireAccessToken()
        val invitation = PairingInvitation.create(
            identity = identity,
            atlasUserId = userId,
            deviceId = deviceIdFor(identity),
            deviceEpoch = 1L,
            displayName = displayName,
            nowEpochSeconds = nowEpochSeconds,
        )
        try {
            remote.createContactInvite(
                invitationId = invitation.invitationId,
                tokenB64 = Base64.getEncoder().encodeToString(invitation.token),
                expiresAtEpochSeconds = invitation.expiresAtEpochSeconds,
                accessToken = token,
            )
        } catch (failure: Exception) {
            throw ChatPairingException("Could not create the pairing invite", failure)
        }
        return invitation
    }

    /** B's side, plan §8.3 steps 2-3: validates the scanned/tapped card, then claims it — the
     *  single-winner guarantee (T-04) lives entirely server-side in `atlas_chat_claim_contact_invite`;
     *  a losing concurrent claim surfaces here as a [ChatPairingException]. The fingerprint is
     *  computed immediately, with no extra round trip, from the card's own public key. */
    suspend fun acceptInvite(cardBytes: ByteArray, nowEpochSeconds: Long): AcceptedInvite {
        val invitation = PairingInvitation.parse(cardBytes, nowEpochSeconds)
        val identity = ensureDeviceRegistered()
        val token = requireAccessToken()
        try {
            remote.claimContactInvite(
                invitationId = invitation.invitationId,
                tokenB64 = Base64.getEncoder().encodeToString(invitation.token),
                accessToken = token,
            )
        } catch (failure: Exception) {
            throw ChatPairingException("Could not claim this invite", failure)
        }
        val fingerprint = PairingFingerprint.compute(identity.publicKeyBytes, invitation.publicKeyBytes, invitation.invitationId)
        return AcceptedInvite(invitation, fingerprint)
    }

    /** A's side, plan §8.3 steps 3-5: one-shot check for whether anyone has claimed [invitation]
     *  yet. Returns null while it's still pending — not an error, just "nothing to show yet". */
    suspend fun fetchClaim(invitation: PairingInvitation): ClaimedInvite? {
        val token = requireAccessToken()
        val claim = remote.fetchInviteClaim(invitation.invitationId, token) ?: return null
        val remoteUserId = claim.claimedByUserId ?: return null
        val remoteDeviceId = claim.claimedByDeviceId ?: return null
        val remotePublicKeyB64 = remote.fetchDeviceIdentityPublicKeyB64(remoteDeviceId, token)
            ?: throw ChatPairingException("Claimant's device identity is not visible yet")
        val identity = identityStore.load() ?: throw ChatPairingException("No local identity to compare against")
        val remotePublicKeyBytes = Base64.getDecoder().decode(remotePublicKeyB64)
        return ClaimedInvite(
            remoteUserId = remoteUserId,
            remoteDeviceId = remoteDeviceId,
            remotePublicKeyBytes = remotePublicKeyBytes,
            fingerprint = PairingFingerprint.compute(identity.publicKeyBytes, remotePublicKeyBytes, invitation.invitationId),
        )
    }

    /** A's side, plan §8.3 steps 7-8: call this only after the user has confirmed the presencial
     *  fingerprint comparison matched — this is what actually fixes the identities server-side
     *  (`atlas_chat_confirm_contact_relationship`) and then mirrors that locally by establishing
     *  and persisting the ratchet session. [contactDisplayName] is what THIS device will call the
     *  new connection from now on — not something B self-reports (nothing in the current protocol
     *  carries B's own chosen name back to A; see [ChatContactStore]'s own doc) but a name A
     *  picks, the same way most contact-adding flows let you label a new contact yourself. */
    suspend fun confirmAndEstablish(invitation: PairingInvitation, claim: ClaimedInvite, contactDisplayName: String): ChatSession {
        val identity = identityStore.load() ?: throw ChatPairingException("No local identity to establish a session with")
        val userId = requireUserId()
        val token = requireAccessToken()
        try {
            remote.confirmContactRelationship(invitation.invitationId, token)
        } catch (failure: Exception) {
            throw ChatPairingException("Could not confirm the pairing", failure)
        }
        val session = ChatSession.establish(
            local = identity,
            localUserId = userId,
            localDeviceId = deviceIdFor(identity),
            remotePublicKeyBytes = claim.remotePublicKeyBytes,
            remoteUserId = claim.remoteUserId,
            remoteDeviceId = claim.remoteDeviceId,
            conversationId = invitation.invitationId,
        )
        sessionStore.persistSession(session.snapshot())
        contactStore.save(
            ChatContact(
                conversationId = invitation.invitationId,
                displayName = contactDisplayName,
                remoteUserId = claim.remoteUserId,
                remoteDeviceId = claim.remoteDeviceId,
                pairedAtEpochSeconds = nowEpochSeconds(),
                verifiedFingerprint = claim.fingerprint,
            ),
        )
        return session
    }

    /** B's side, plan §8.3 steps 7-8: one-shot check for whether A has confirmed yet. Returns null
     *  while still waiting — not an error. Once true, establishes and persists B's own session;
     *  B needs no extra read for the remote public key, it already has A's from [accepted]'s
     *  card. [contactDisplayName] is what B will call this connection — the pairing screen
     *  prefills it with [AcceptedInvite.invitation]'s own `displayName` (A's self-declared name,
     *  already on the card B scanned) since that's a far more useful default here than A's side
     *  has available, but the user can still edit it before confirming. */
    suspend fun waitForConfirmation(accepted: AcceptedInvite, contactDisplayName: String): ChatSession? {
        val token = requireAccessToken()
        if (!remote.isRelationshipActive(accepted.invitation.invitationId, token)) return null
        val identity = identityStore.load() ?: throw ChatPairingException("No local identity to establish a session with")
        val userId = requireUserId()
        val session = ChatSession.establish(
            local = identity,
            localUserId = userId,
            localDeviceId = deviceIdFor(identity),
            remotePublicKeyBytes = accepted.invitation.publicKeyBytes,
            remoteUserId = accepted.invitation.atlasUserId,
            remoteDeviceId = accepted.invitation.deviceId,
            conversationId = accepted.invitation.invitationId,
        )
        sessionStore.persistSession(session.snapshot())
        contactStore.save(
            ChatContact(
                conversationId = accepted.invitation.invitationId,
                displayName = contactDisplayName,
                remoteUserId = accepted.invitation.atlasUserId,
                remoteDeviceId = accepted.invitation.deviceId,
                pairedAtEpochSeconds = nowEpochSeconds(),
                verifiedFingerprint = accepted.fingerprint,
            ),
        )
        return session
    }
}
