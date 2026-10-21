package com.unyx.atlas.security

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Ciphertext stored by the server. The recovery code is deliberately never part of this object. */
data class RecoveryEnvelope(
    val version: Int,
    val algorithm: String,
    val kdf: String,
    val keyId: String,
    val saltB64: String,
    val nonceB64: String,
    val ciphertextB64: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("v", version)
        .put("alg", algorithm)
        .put("kdf", kdf)
        .put("key_id", keyId)
        .put("salt_b64", saltB64)
        .put("nonce_b64", nonceB64)
        .put("ciphertext_b64", ciphertextB64)

    companion object {
        fun fromJson(json: JSONObject): RecoveryEnvelope = RecoveryEnvelope(
            version = json.getInt("v"),
            algorithm = json.getString("alg"),
            kdf = json.getString("kdf"),
            keyId = json.getString("key_id"),
            saltB64 = json.getString("salt_b64"),
            nonceB64 = json.getString("nonce_b64"),
            ciphertextB64 = json.getString("ciphertext_b64"),
        )

        fun parse(json: String): RecoveryEnvelope = fromJson(JSONObject(json))
    }
}

/**
 * Encrypts the account root key with a key derived from a randomly generated 256-bit recovery
 * code. The server receives only this authenticated envelope. HKDF-SHA256 is used because the
 * recovery code has high entropy; it is not a human-chosen password.
 */
object AtlasRecoveryEnvelopeCrypto {
    private const val VERSION = 1
    private const val ALGORITHM = "AES-256-GCM"
    private const val KDF = "HKDF-SHA256"
    private const val DOMAIN = "ATLAS-ARK-RECOVERY"
    private const val INFO = "ATLAS-ARK-RECOVERY-V1"
    private const val AES = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val HMAC = "HmacSHA256"
    private const val KEY_BYTES = 32
    private const val SALT_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    fun newRecoveryCode(): Pair<String, ByteArray> {
        val raw = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        val code = Base64.getUrlEncoder().withoutPadding().encodeToString(raw).chunked(6).joinToString(" ")
        return code to raw
    }

    fun parseRecoveryCode(value: String): ByteArray {
        val compact = value.filterNot { it.isWhitespace() }
        val decoded = try {
            Base64.getUrlDecoder().decode(compact)
        } catch (_: IllegalArgumentException) {
            throw GeneralSecurityException("Código de recuperação inválido")
        }
        if (decoded.size != KEY_BYTES) {
            Arrays.fill(decoded, 0)
            throw GeneralSecurityException("Código de recuperação inválido")
        }
        return decoded
    }

    @Throws(GeneralSecurityException::class)
    fun wrapAccountRootKey(
        accountRootKey: ByteArray,
        recoveryCodeBytes: ByteArray,
        userId: String,
        keyId: String,
    ): RecoveryEnvelope {
        require(accountRootKey.size == KEY_BYTES) { "Account root key must be 256 bits" }
        require(recoveryCodeBytes.size == KEY_BYTES) { "Recovery code must be 256 bits" }
        require(userId.isNotBlank() && keyId.isNotBlank())
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val recoveryKey = hkdf(recoveryCodeBytes, salt, INFO.toByteArray(StandardCharsets.UTF_8))
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(recoveryKey, AES),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            cipher.updateAAD(associatedData(userId, keyId))
            return RecoveryEnvelope(
                version = VERSION,
                algorithm = ALGORITHM,
                kdf = KDF,
                keyId = keyId,
                saltB64 = encode(salt),
                nonceB64 = encode(nonce),
                ciphertextB64 = encode(cipher.doFinal(accountRootKey)),
            )
        } finally {
            Arrays.fill(recoveryKey, 0)
            Arrays.fill(salt, 0)
            Arrays.fill(nonce, 0)
        }
    }

    @Throws(GeneralSecurityException::class)
    fun unwrapAccountRootKey(
        envelope: RecoveryEnvelope,
        recoveryCodeBytes: ByteArray,
        userId: String,
    ): ByteArray {
        validate(envelope)
        require(recoveryCodeBytes.size == KEY_BYTES) { "Recovery code must be 256 bits" }
        val salt = decode(envelope.saltB64)
        val nonce = decode(envelope.nonceB64)
        val ciphertext = decode(envelope.ciphertextB64)
        try {
            if (salt.size != SALT_BYTES || nonce.size != NONCE_BYTES || ciphertext.size != KEY_BYTES + TAG_BITS / 8) {
                throw GeneralSecurityException("Invalid recovery envelope")
            }
            val recoveryKey = hkdf(recoveryCodeBytes, salt, INFO.toByteArray(StandardCharsets.UTF_8))
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(recoveryKey, AES), GCMParameterSpec(TAG_BITS, nonce))
                cipher.updateAAD(associatedData(userId, envelope.keyId))
                return cipher.doFinal(ciphertext).also { rootKey ->
                    if (rootKey.size != KEY_BYTES) {
                        Arrays.fill(rootKey, 0)
                        throw GeneralSecurityException("Invalid recovered account key")
                    }
                }
            } finally {
                Arrays.fill(recoveryKey, 0)
            }
        } finally {
            Arrays.fill(salt, 0)
            Arrays.fill(nonce, 0)
            Arrays.fill(ciphertext, 0)
        }
    }

    private fun validate(envelope: RecoveryEnvelope) {
        require(envelope.version == VERSION) { "Unsupported recovery envelope version" }
        require(envelope.algorithm == ALGORITHM) { "Unsupported recovery envelope algorithm" }
        require(envelope.kdf == KDF) { "Unsupported recovery key derivation" }
        require(envelope.keyId.isNotBlank()) { "Recovery envelope has no key id" }
    }

    private fun associatedData(userId: String, keyId: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            writeField(output, DOMAIN)
            output.writeInt(VERSION)
            writeField(output, userId)
            writeField(output, keyId)
            writeField(output, ALGORITHM)
            writeField(output, KDF)
        }
        return bytes.toByteArray()
    }

    private fun hkdf(inputKeyMaterial: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(salt, HMAC))
        val pseudoRandomKey = mac.doFinal(inputKeyMaterial)
        try {
            mac.init(SecretKeySpec(pseudoRandomKey, HMAC))
            mac.update(info)
            mac.update(1.toByte())
            return mac.doFinal().copyOf(KEY_BYTES)
        } finally {
            Arrays.fill(pseudoRandomKey, 0)
        }
    }

    private fun writeField(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        output.writeInt(bytes.size)
        output.write(bytes)
        Arrays.fill(bytes, 0)
    }

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun decode(value: String): ByteArray = try {
        Base64.getUrlDecoder().decode(value)
    } catch (_: IllegalArgumentException) {
        throw GeneralSecurityException("Invalid recovery envelope encoding")
    }
}
