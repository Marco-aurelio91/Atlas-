package com.unyx.atlas.chatcrypto

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val KEY_ALGORITHM = "AES"
private const val GCM_NONCE_BYTES = 12
private const val GCM_TAG_BITS = 128
private const val ENVELOPE_VERSION = 1
private const val DOMAIN = "ATLAS-CHAT-ENVELOPE"

/** One encrypted message on the wire — see `docs/ATLAS_CHAT_DESENHO_CRIPTOGRAFICO.md` §6.
 *  Everything except [ciphertextAndTag] is also authenticated (bound into the GCM associated
 *  data), so the server — or anyone else — tampering with the conversation/sender/sequence a
 *  stored envelope claims to belong to makes decryption fail outright, not silently misfile it. */
data class ChatEnvelope(
    val version: Int,
    val conversationId: String,
    val senderDeviceId: String,
    val sequence: Long,
    val nonce: ByteArray,
    val ciphertextAndTag: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is ChatEnvelope && version == other.version && conversationId == other.conversationId &&
            senderDeviceId == other.senderDeviceId && sequence == other.sequence &&
            nonce.contentEquals(other.nonce) && ciphertextAndTag.contentEquals(other.ciphertextAndTag)

    override fun hashCode(): Int =
        Arrays.hashCode(intArrayOf(version, conversationId.hashCode(), senderDeviceId.hashCode(), sequence.hashCode())) * 31 +
            nonce.contentHashCode()
}

/** Encrypts [plaintext] with [messageKey] (one-time use — a fresh key per message from the
 *  ratchet, see [RatchetChain.advance]) into a [ChatEnvelope] for [conversationId]/[senderDeviceId]
 *  at [sequence]. The caller owns [messageKey] and should zero it right after this call. */
internal fun encryptEnvelope(
    messageKey: ByteArray,
    plaintext: ByteArray,
    conversationId: String,
    senderDeviceId: String,
    sequence: Long,
): ChatEnvelope {
    require(messageKey.size == 32) { "Message key must be 256 bits" }
    val nonce = ByteArray(GCM_NONCE_BYTES).also(SecureRandom()::nextBytes)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(messageKey, KEY_ALGORITHM), GCMParameterSpec(GCM_TAG_BITS, nonce))
    cipher.updateAAD(associatedData(conversationId, senderDeviceId, sequence))
    val ciphertextAndTag = cipher.doFinal(plaintext)
    return ChatEnvelope(ENVELOPE_VERSION, conversationId, senderDeviceId, sequence, nonce, ciphertextAndTag)
}

/** Decrypts [envelope] with [messageKey]. Throws [GeneralSecurityException] (GCM auth failure) on
 *  any tampering — of the ciphertext itself, or of the conversation/sender/sequence the caller
 *  claims the envelope belongs to, since those are authenticated too (T-09 in the plan's test
 *  matrix). The caller owns [messageKey] and should zero it right after this call. */
internal fun decryptEnvelope(messageKey: ByteArray, envelope: ChatEnvelope): ByteArray {
    require(messageKey.size == 32) { "Message key must be 256 bits" }
    require(envelope.version == ENVELOPE_VERSION) { "Unsupported Atlas Chat envelope version" }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(
        Cipher.DECRYPT_MODE,
        SecretKeySpec(messageKey, KEY_ALGORITHM),
        GCMParameterSpec(GCM_TAG_BITS, envelope.nonce),
    )
    cipher.updateAAD(associatedData(envelope.conversationId, envelope.senderDeviceId, envelope.sequence))
    return cipher.doFinal(envelope.ciphertextAndTag)
}

private fun associatedData(conversationId: String, senderDeviceId: String, sequence: Long): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { data ->
        writeField(data, DOMAIN)
        data.writeInt(ENVELOPE_VERSION)
        writeField(data, conversationId)
        writeField(data, senderDeviceId)
        data.writeLong(sequence)
    }
    return bytes.toByteArray()
}

private fun writeField(output: DataOutputStream, value: String) {
    val encoded = value.toByteArray(StandardCharsets.UTF_8)
    output.writeInt(encoded.size)
    output.write(encoded)
}
