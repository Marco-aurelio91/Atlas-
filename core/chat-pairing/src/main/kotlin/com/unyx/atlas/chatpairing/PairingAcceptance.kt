package com.unyx.atlas.chatpairing

import com.unyx.atlas.chatcrypto.ChatIdentityKeyPair
import org.json.JSONException
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Device B's reply to a [PairingInvitation] — §8.3 steps 3-4 of the plan: B "resgata" (claims)
 *  the invite and A "vê a solicitação e confirma B". The plan has this round trip go through the
 *  server (claim → server records it → A's app sees the pending request and confirms), which
 *  isn't built yet (no schema/backend in this prototype); this class models the *shape* of what
 *  has to reach A one way or another — B's own identity public key, bound to the specific
 *  [invitationId] it's accepting so a confirmation can't be replayed against an unrelated invite.
 *
 *  Transport-agnostic on purpose, same as [PairingInvitation] — how these bytes get from B back to
 *  A (server claim today, something else later) is `chat:remote`'s job, not this module's. */
data class PairingAcceptance(
    val formatVersion: Int,
    val invitationId: String,
    val atlasUserId: String,
    val deviceId: String,
    val deviceEpoch: Long,
    val publicKeyBytes: ByteArray,
    val displayName: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put(FIELD_FORMAT_VERSION, formatVersion)
        .put(FIELD_INVITATION_ID, invitationId)
        .put(FIELD_USER_ID, atlasUserId)
        .put(FIELD_DEVICE_ID, deviceId)
        .put(FIELD_DEVICE_EPOCH, deviceEpoch)
        .put(FIELD_PUBLIC_KEY, encode(publicKeyBytes))
        .put(FIELD_DISPLAY_NAME, displayName)

    fun serialize(): ByteArray = toJson().toString().toByteArray(StandardCharsets.UTF_8)

    companion object {
        const val CURRENT_FORMAT_VERSION = 1

        private const val FIELD_FORMAT_VERSION = "format_version"
        private const val FIELD_INVITATION_ID = "invitation_id"
        private const val FIELD_USER_ID = "atlas_user_id"
        private const val FIELD_DEVICE_ID = "device_id"
        private const val FIELD_DEVICE_EPOCH = "device_epoch"
        private const val FIELD_PUBLIC_KEY = "public_key_b64"
        private const val FIELD_DISPLAY_NAME = "display_name"

        /** Builds B's acceptance for a [PairingInvitation] B has already validated (expiry,
         *  format) — this constructor doesn't re-check those; call [PairingInvitation.parse]
         *  first, same as the real flow (B reads and validates the card before deciding to
         *  claim it). */
        fun accept(
            invitation: PairingInvitation,
            identity: ChatIdentityKeyPair,
            atlasUserId: String,
            deviceId: String,
            deviceEpoch: Long,
            displayName: String,
        ): PairingAcceptance {
            require(displayName.isNotBlank()) { "displayName must not be blank" }
            require(displayName.length <= PairingInvitation.MAX_DISPLAY_NAME_CHARS) { "displayName too long" }
            return PairingAcceptance(
                formatVersion = CURRENT_FORMAT_VERSION,
                invitationId = invitation.invitationId,
                atlasUserId = atlasUserId,
                deviceId = deviceId,
                deviceEpoch = deviceEpoch,
                publicKeyBytes = identity.publicKeyBytes,
                displayName = displayName,
            )
        }

        /** Parses bytes received back on A's side. [expectedInvitationId] binds this to the exact
         *  invite A sent out — an acceptance for a *different* invitation (stale, or a mismatched
         *  claim) is rejected rather than silently accepted. */
        fun parse(bytes: ByteArray, expectedInvitationId: String): PairingAcceptance {
            val json = try {
                JSONObject(String(bytes, StandardCharsets.UTF_8))
            } catch (malformed: JSONException) {
                throw InvalidPairingCardException("Acceptance is not valid JSON")
            }
            val acceptance = try {
                val formatVersion = json.getInt(FIELD_FORMAT_VERSION)
                if (formatVersion != CURRENT_FORMAT_VERSION) {
                    throw InvalidPairingCardException("Unsupported acceptance format version $formatVersion")
                }
                PairingAcceptance(
                    formatVersion = formatVersion,
                    invitationId = json.getString(FIELD_INVITATION_ID),
                    atlasUserId = json.getString(FIELD_USER_ID).also {
                        if (it.isBlank()) throw InvalidPairingCardException("Acceptance user id is missing")
                    },
                    deviceId = json.getString(FIELD_DEVICE_ID).also {
                        if (it.isBlank()) throw InvalidPairingCardException("Acceptance device id is missing")
                    },
                    deviceEpoch = json.getLong(FIELD_DEVICE_EPOCH),
                    publicKeyBytes = decode(json.getString(FIELD_PUBLIC_KEY)),
                    displayName = json.getString(FIELD_DISPLAY_NAME),
                )
            } catch (missingOrWrongType: JSONException) {
                throw InvalidPairingCardException("Acceptance is missing a required field")
            } catch (badEncoding: IllegalArgumentException) {
                throw InvalidPairingCardException("Acceptance has invalid encoding")
            }
            if (acceptance.invitationId != expectedInvitationId) {
                throw InvalidPairingCardException("Acceptance does not match the invitation it claims to answer")
            }
            return acceptance
        }

        private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
        private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)
    }

    override fun equals(other: Any?): Boolean =
        other is PairingAcceptance && formatVersion == other.formatVersion && invitationId == other.invitationId &&
            atlasUserId == other.atlasUserId && deviceId == other.deviceId && deviceEpoch == other.deviceEpoch &&
            publicKeyBytes.contentEquals(other.publicKeyBytes) && displayName == other.displayName

    override fun hashCode(): Int = invitationId.hashCode() * 31 + publicKeyBytes.contentHashCode()
}
