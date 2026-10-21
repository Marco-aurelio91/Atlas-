package com.unyx.atlas.chatpairing

import com.unyx.atlas.chatcrypto.ChatIdentityKeyPair
import org.json.JSONException
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Any problem with a convite/acceptance card — malformed JSON, wrong version, oversized payload,
 *  expired, or a required field missing. Deliberately one exception type for all of these: per
 *  `docs/ATLAS_CHAT_Plano_Completo_Criacao_e_Implantacao.md` §8.3, "ao cancelar, expirar, mudar
 *  identidade ou detectar divergência: invalidar tentativa" — the UI only needs to know pairing
 *  failed safely (T-05), not which of several parsing edge cases caused it. */
class InvalidPairingCardException(message: String) : Exception(message)

/** The invite card §8.2 of the plan describes: what device A presents over NFC/QR for device B to
 *  read. Carries A's chat identity public key (not the account root key — see
 *  `docs/ATLAS_CHAT_DESENHO_CRIPTOGRAFICO.md` §3) so B can perform the X25519 agreement once the
 *  bilateral exchange (this card, plus B's own [PairingAcceptance] going back to A — see that
 *  class's doc) completes.
 *
 *  [token] is a one-time secret matching the plan's description ("segredo de uso único, não a
 *  chave da conversa") — its *server-side* role (hash stored, single claim enforced, expiry
 *  checked by the server's clock) needs the backend this prototype doesn't have yet; here it's
 *  carried and format-validated, nothing more. Don't mistake this class validating the card's
 *  *shape* for the server-side claim/expiry enforcement the real system also needs (T-04's
 *  concurrent-claim scenario is a server concern, not testable from this module alone).
 */
data class PairingInvitation(
    val formatVersion: Int,
    val invitationId: String,
    val token: ByteArray,
    val expiresAtEpochSeconds: Long,
    val atlasUserId: String,
    val deviceId: String,
    val deviceEpoch: Long,
    val publicKeyBytes: ByteArray,
    val displayName: String,
) {
    fun isExpired(nowEpochSeconds: Long): Boolean = nowEpochSeconds >= expiresAtEpochSeconds

    fun toJson(): JSONObject = JSONObject()
        .put(FIELD_FORMAT_VERSION, formatVersion)
        .put(FIELD_INVITATION_ID, invitationId)
        .put(FIELD_TOKEN, encode(token))
        .put(FIELD_EXPIRES_AT, expiresAtEpochSeconds)
        .put(FIELD_USER_ID, atlasUserId)
        .put(FIELD_DEVICE_ID, deviceId)
        .put(FIELD_DEVICE_EPOCH, deviceEpoch)
        .put(FIELD_PUBLIC_KEY, encode(publicKeyBytes))
        .put(FIELD_DISPLAY_NAME, displayName)

    /** The exact bytes that would go over NFC/QR — what [parse] expects back. UTF-8 JSON, not some
     *  denser binary framing: simplicity over a few saved bytes while this is still a prototype
     *  with no real transport wired up; §8.2's 4 KiB bound is checked against *this*, not the
     *  in-memory object. */
    fun serialize(): ByteArray = toJson().toString().toByteArray(StandardCharsets.UTF_8)

    companion object {
        const val MAX_CARD_BYTES = 4096
        const val CURRENT_FORMAT_VERSION = 1
        const val MAX_DISPLAY_NAME_CHARS = 60
        private const val TOKEN_BYTES = 32 // 256 bits, per the plan's §8.2 minimum
        private const val DEFAULT_VALIDITY_SECONDS = 5 * 60L // five minutes, per §8.2

        private const val FIELD_FORMAT_VERSION = "format_version"
        private const val FIELD_INVITATION_ID = "invitation_id"
        private const val FIELD_TOKEN = "token_b64"
        private const val FIELD_EXPIRES_AT = "expires_at"
        private const val FIELD_USER_ID = "atlas_user_id"
        private const val FIELD_DEVICE_ID = "device_id"
        private const val FIELD_DEVICE_EPOCH = "device_epoch"
        private const val FIELD_PUBLIC_KEY = "public_key_b64"
        private const val FIELD_DISPLAY_NAME = "display_name"

        /** Creates a fresh invite for [identity] to present over NFC/QR. [nowEpochSeconds] is
         *  taken as a parameter (not read from the system clock here) so callers — and this
         *  module's tests — control it explicitly; the plan is explicit that client-side
         *  expiration is a UX nicety, the server's own clock is what's actually authoritative. */
        fun create(
            identity: ChatIdentityKeyPair,
            atlasUserId: String,
            deviceId: String,
            deviceEpoch: Long,
            displayName: String,
            nowEpochSeconds: Long,
            validitySeconds: Long = DEFAULT_VALIDITY_SECONDS,
        ): PairingInvitation {
            require(displayName.isNotBlank()) { "displayName must not be blank" }
            require(displayName.length <= MAX_DISPLAY_NAME_CHARS) { "displayName too long" }
            val token = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)
            val invitation = PairingInvitation(
                formatVersion = CURRENT_FORMAT_VERSION,
                invitationId = UUID.randomUUID().toString(),
                token = token,
                expiresAtEpochSeconds = nowEpochSeconds + validitySeconds,
                atlasUserId = atlasUserId,
                deviceId = deviceId,
                deviceEpoch = deviceEpoch,
                publicKeyBytes = identity.publicKeyBytes,
                displayName = displayName,
            )
            // A card that can't even serialize within the transport bound is a bug here, not
            // something the far side should have to detect — fail now, not after a QR with
            // truncated data gets scanned.
            check(invitation.serialize().size <= MAX_CARD_BYTES) { "Generated invitation exceeds the ${MAX_CARD_BYTES}-byte card limit" }
            return invitation
        }

        /** Parses and validates bytes read off NFC/QR. Throws [InvalidPairingCardException] for
         *  any problem — oversized payload, malformed JSON, unsupported version, a blank/missing
         *  required field, or (if [nowEpochSeconds] is given) an already-expired card — the plan's
         *  T-05 ("Convite expirado ou QR alterado → Rejeição segura"). Never throws anything else:
         *  arbitrary bytes from a scanned/tapped source are untrusted input by definition. */
        fun parse(bytes: ByteArray, nowEpochSeconds: Long? = null): PairingInvitation {
            if (bytes.size > MAX_CARD_BYTES) {
                throw InvalidPairingCardException("Invitation card exceeds the $MAX_CARD_BYTES-byte limit")
            }
            val json = try {
                JSONObject(String(bytes, StandardCharsets.UTF_8))
            } catch (malformed: JSONException) {
                throw InvalidPairingCardException("Invitation card is not valid JSON")
            }
            val invitation = try {
                val formatVersion = json.getInt(FIELD_FORMAT_VERSION)
                if (formatVersion != CURRENT_FORMAT_VERSION) {
                    throw InvalidPairingCardException("Unsupported invitation format version $formatVersion")
                }
                val token = decode(json.getString(FIELD_TOKEN))
                if (token.size != TOKEN_BYTES) {
                    throw InvalidPairingCardException("Invitation token has an unexpected length")
                }
                val publicKeyBytes = decode(json.getString(FIELD_PUBLIC_KEY))
                val displayName = json.getString(FIELD_DISPLAY_NAME)
                if (displayName.isBlank() || displayName.length > MAX_DISPLAY_NAME_CHARS) {
                    throw InvalidPairingCardException("Invitation display name is missing or too long")
                }
                PairingInvitation(
                    formatVersion = formatVersion,
                    invitationId = json.getString(FIELD_INVITATION_ID).also {
                        if (it.isBlank()) throw InvalidPairingCardException("Invitation id is missing")
                    },
                    token = token,
                    expiresAtEpochSeconds = json.getLong(FIELD_EXPIRES_AT),
                    atlasUserId = json.getString(FIELD_USER_ID).also {
                        if (it.isBlank()) throw InvalidPairingCardException("Invitation user id is missing")
                    },
                    deviceId = json.getString(FIELD_DEVICE_ID).also {
                        if (it.isBlank()) throw InvalidPairingCardException("Invitation device id is missing")
                    },
                    deviceEpoch = json.getLong(FIELD_DEVICE_EPOCH),
                    publicKeyBytes = publicKeyBytes,
                    displayName = displayName,
                )
            } catch (missingOrWrongType: JSONException) {
                throw InvalidPairingCardException("Invitation card is missing a required field")
            } catch (badEncoding: IllegalArgumentException) {
                throw InvalidPairingCardException("Invitation card has invalid encoding")
            }
            if (nowEpochSeconds != null && invitation.isExpired(nowEpochSeconds)) {
                throw InvalidPairingCardException("Invitation has expired")
            }
            return invitation
        }

        private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
        private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)
    }

    override fun equals(other: Any?): Boolean =
        other is PairingInvitation && formatVersion == other.formatVersion && invitationId == other.invitationId &&
            token.contentEquals(other.token) && expiresAtEpochSeconds == other.expiresAtEpochSeconds &&
            atlasUserId == other.atlasUserId && deviceId == other.deviceId && deviceEpoch == other.deviceEpoch &&
            publicKeyBytes.contentEquals(other.publicKeyBytes) && displayName == other.displayName

    override fun hashCode(): Int {
        var result = invitationId.hashCode()
        result = 31 * result + token.contentHashCode()
        result = 31 * result + publicKeyBytes.contentHashCode()
        return result
    }
}
