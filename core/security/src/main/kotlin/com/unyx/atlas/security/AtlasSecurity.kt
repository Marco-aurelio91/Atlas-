package com.unyx.atlas.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Stable identity for the value being encrypted. These fields are authenticated as AES-GCM AAD,
 * so moving an envelope to another user, record, or data type makes decryption fail.
 */
data class CryptoContext(
    val userId: String,
    val objectId: String,
    val objectType: String,
) {
    init {
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(objectId.isNotBlank()) { "objectId must not be blank" }
        require(objectType.isNotBlank()) { "objectType must not be blank" }
    }
}

/**
 * Versioned AES-GCM envelope. `ciphertextB64` includes the GCM authentication tag.
 * The DEK is unique per envelope and itself encrypted by the non-exportable Android Keystore key.
 */
data class EncryptedEnvelope(
    val version: Int,
    val algorithm: String,
    val keyId: String,
    val aadSchema: Int,
    val nonceB64: String,
    val ciphertextB64: String,
    val wrappedKeyB64: String,
    val wrapNonceB64: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("v", version)
        .put("alg", algorithm)
        .put("key_id", keyId)
        .put("aad_schema", aadSchema)
        .put("nonce_b64", nonceB64)
        .put("ciphertext_b64", ciphertextB64)
        .put("wrapped_key_b64", wrappedKeyB64)
        .put("wrap_nonce_b64", wrapNonceB64)

    companion object {
        fun fromJson(json: JSONObject): EncryptedEnvelope = EncryptedEnvelope(
            version = json.getInt("v"),
            algorithm = json.getString("alg"),
            keyId = json.getString("key_id"),
            aadSchema = json.getInt("aad_schema"),
            nonceB64 = json.getString("nonce_b64"),
            ciphertextB64 = json.getString("ciphertext_b64"),
            wrappedKeyB64 = json.getString("wrapped_key_b64"),
            wrapNonceB64 = json.getString("wrap_nonce_b64"),
        )

        fun parse(json: String): EncryptedEnvelope = fromJson(JSONObject(json))
    }
}

/**
 * Atlas local cryptography foundation.
 *
 * A fresh AES-256 DEK encrypts each value with GCM. The DEK is wrapped with a device key held by
 * Android Keystore and never exported. This protects local ciphertext at rest; because the wrapping
 * key is device-bound, this alone is not cloud synchronization, account recovery, or E2EE.
 */
class AtlasSecurity {
    @Synchronized
    @Throws(GeneralSecurityException::class)
    fun encrypt(plaintext: ByteArray, context: CryptoContext): EncryptedEnvelope {
        return encryptWithWrappingKey(
            plaintext = plaintext,
            context = context,
            keyId = DEVICE_KEY_ID,
            wrappingPurpose = PURPOSE_WRAPPED_KEY,
            wrappingKey = getOrCreateDeviceWrappingKey(),
        )
    }

    /** Encrypts an object with a DEK wrapped by the account root key. The root key is never stored
     * in this envelope; callers must load it from the local Keystore-protected store and clear it
     * from memory after use. */
    @Synchronized
    @Throws(GeneralSecurityException::class)
    fun encryptForAccount(
        plaintext: ByteArray,
        context: CryptoContext,
        accountKeyId: String,
        accountRootKey: ByteArray,
    ): EncryptedEnvelope {
        require(accountKeyId.isNotBlank()) { "accountKeyId must not be blank" }
        require(accountRootKey.size == DEK_SIZE_BYTES) { "Account root key must be 256 bits" }
        val wrappingKeyBytes = accountRootKey.copyOf()
        try {
            return encryptWithWrappingKey(
                plaintext = plaintext,
                context = context,
                keyId = accountEnvelopeKeyId(accountKeyId),
                wrappingPurpose = PURPOSE_ACCOUNT_WRAPPED_KEY,
                wrappingKey = SecretKeySpec(wrappingKeyBytes, KEY_ALGORITHM),
            )
        } finally {
            Arrays.fill(wrappingKeyBytes, 0)
        }
    }

    private fun encryptWithWrappingKey(
        plaintext: ByteArray,
        context: CryptoContext,
        keyId: String,
        wrappingPurpose: String,
        wrappingKey: SecretKey,
    ): EncryptedEnvelope {
        val dek = ByteArray(DEK_SIZE_BYTES).also(SecureRandom()::nextBytes)
        try {
            val contentCipher = Cipher.getInstance(TRANSFORMATION)
            contentCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, KEY_ALGORITHM))
            val contentNonce = requireNotNull(contentCipher.iv) { "AES-GCM did not provide a nonce" }
            contentCipher.updateAAD(associatedData(context, PURPOSE_CONTENT, keyId))
            val ciphertext = contentCipher.doFinal(plaintext)

            val wrappingCipher = Cipher.getInstance(TRANSFORMATION)
            wrappingCipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
            val wrapNonce = requireNotNull(wrappingCipher.iv) { "Keystore AES-GCM did not provide a nonce" }
            wrappingCipher.updateAAD(associatedData(context, wrappingPurpose, keyId))
            val wrappedKey = wrappingCipher.doFinal(dek)

            return EncryptedEnvelope(
                version = ENVELOPE_VERSION,
                algorithm = ALGORITHM_LABEL,
                keyId = keyId,
                aadSchema = AAD_SCHEMA_VERSION,
                nonceB64 = encode(contentNonce),
                ciphertextB64 = encode(ciphertext),
                wrappedKeyB64 = encode(wrappedKey),
                wrapNonceB64 = encode(wrapNonce),
            )
        } finally {
            Arrays.fill(dek, 0)
        }
    }

    @Synchronized
    @Throws(GeneralSecurityException::class)
    fun decrypt(envelope: EncryptedEnvelope, context: CryptoContext): ByteArray {
        validateEnvelope(envelope, DEVICE_KEY_ID)
        val wrappingCipher = Cipher.getInstance(TRANSFORMATION)
        wrappingCipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateDeviceWrappingKey(),
            GCMParameterSpec(GCM_TAG_SIZE_BITS, decode(envelope.wrapNonceB64)),
        )
        wrappingCipher.updateAAD(associatedData(context, PURPOSE_WRAPPED_KEY, DEVICE_KEY_ID))
        val dek = wrappingCipher.doFinal(decode(envelope.wrappedKeyB64))
        try {
            return decryptContent(envelope, context, DEVICE_KEY_ID, dek)
        } finally {
            Arrays.fill(dek, 0)
        }
    }

    @Synchronized
    @Throws(GeneralSecurityException::class)
    fun decryptForAccount(
        envelope: EncryptedEnvelope,
        context: CryptoContext,
        accountKeyId: String,
        accountRootKey: ByteArray,
    ): ByteArray {
        require(accountKeyId.isNotBlank()) { "accountKeyId must not be blank" }
        require(accountRootKey.size == DEK_SIZE_BYTES) { "Account root key must be 256 bits" }
        val expectedKeyId = accountEnvelopeKeyId(accountKeyId)
        validateEnvelope(envelope, expectedKeyId)
        val wrappingKeyBytes = accountRootKey.copyOf()
        val dek = try {
            val wrappingCipher = Cipher.getInstance(TRANSFORMATION)
            wrappingCipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(wrappingKeyBytes, KEY_ALGORITHM),
                GCMParameterSpec(GCM_TAG_SIZE_BITS, decode(envelope.wrapNonceB64)),
            )
            wrappingCipher.updateAAD(associatedData(context, PURPOSE_ACCOUNT_WRAPPED_KEY, expectedKeyId))
            wrappingCipher.doFinal(decode(envelope.wrappedKeyB64))
        } finally {
            Arrays.fill(wrappingKeyBytes, 0)
        }
        try {
            return decryptContent(envelope, context, expectedKeyId, dek)
        } finally {
            Arrays.fill(dek, 0)
        }
    }

    fun encryptText(plaintext: String, context: CryptoContext): EncryptedEnvelope =
        plaintext.toByteArray(StandardCharsets.UTF_8).let { bytes ->
            try { encrypt(bytes, context) } finally { Arrays.fill(bytes, 0) }
        }

    fun decryptText(envelope: EncryptedEnvelope, context: CryptoContext): String =
        decrypt(envelope, context).let { bytes ->
            try { String(bytes, StandardCharsets.UTF_8) } finally { Arrays.fill(bytes, 0) }
        }

    fun encryptTextForAccount(
        plaintext: String,
        context: CryptoContext,
        accountKeyId: String,
        accountRootKey: ByteArray,
    ): EncryptedEnvelope = plaintext.toByteArray(StandardCharsets.UTF_8).let { bytes ->
        try { encryptForAccount(bytes, context, accountKeyId, accountRootKey) } finally { Arrays.fill(bytes, 0) }
    }

    fun decryptTextForAccount(
        envelope: EncryptedEnvelope,
        context: CryptoContext,
        accountKeyId: String,
        accountRootKey: ByteArray,
    ): String = decryptForAccount(envelope, context, accountKeyId, accountRootKey).let { bytes ->
        try { String(bytes, StandardCharsets.UTF_8) } finally { Arrays.fill(bytes, 0) }
    }

    private fun decryptContent(
        envelope: EncryptedEnvelope,
        context: CryptoContext,
        keyId: String,
        dek: ByteArray,
    ): ByteArray {
        if (dek.size != DEK_SIZE_BYTES) throw GeneralSecurityException("Envelope contains an invalid data key")
        val contentCipher = Cipher.getInstance(TRANSFORMATION)
        contentCipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(dek, KEY_ALGORITHM),
            GCMParameterSpec(GCM_TAG_SIZE_BITS, decode(envelope.nonceB64)),
        )
        contentCipher.updateAAD(associatedData(context, PURPOSE_CONTENT, keyId))
        return contentCipher.doFinal(decode(envelope.ciphertextB64))
    }

    private fun validateEnvelope(envelope: EncryptedEnvelope, expectedKeyId: String) {
        require(envelope.version == ENVELOPE_VERSION) { "Unsupported Atlas envelope version" }
        require(envelope.algorithm == ALGORITHM_LABEL) { "Unsupported Atlas envelope algorithm" }
        require(envelope.keyId == expectedKeyId) { "Envelope belongs to a different wrapping key" }
        require(envelope.aadSchema == AAD_SCHEMA_VERSION) { "Unsupported Atlas AAD schema" }
        require(decode(envelope.nonceB64).size == GCM_NONCE_SIZE_BYTES) { "Invalid content nonce" }
        require(decode(envelope.wrapNonceB64).size == GCM_NONCE_SIZE_BYTES) { "Invalid wrapping nonce" }
    }

    private fun getOrCreateDeviceWrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(DEK_SIZE_BYTES * 8)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun associatedData(context: CryptoContext, purpose: String, keyId: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { data ->
            writeField(data, DOMAIN)
            data.writeInt(ENVELOPE_VERSION)
            data.writeInt(AAD_SCHEMA_VERSION)
            writeField(data, keyId)
            writeField(data, context.userId)
            writeField(data, context.objectId)
            writeField(data, context.objectType)
            writeField(data, purpose)
        }
        return bytes.toByteArray()
    }

    private fun writeField(output: DataOutputStream, value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        output.writeInt(encoded.size)
        output.write(encoded)
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    private fun accountEnvelopeKeyId(accountKeyId: String): String = "atlas-account:$accountKeyId"

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "atlas.security.device_wrapping.v1"
        const val DEVICE_KEY_ID = "atlas-device-v1"
        const val KEY_ALGORITHM = "AES"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ALGORITHM_LABEL = "AES-256-GCM"
        const val ENVELOPE_VERSION = 1
        const val AAD_SCHEMA_VERSION = 1
        const val DEK_SIZE_BYTES = 32
        const val GCM_NONCE_SIZE_BYTES = 12
        const val GCM_TAG_SIZE_BITS = 128
        const val DOMAIN = "ATLAS-SECURITY-ENVELOPE"
        const val PURPOSE_CONTENT = "content"
        const val PURPOSE_WRAPPED_KEY = "wrapped-dek"
        const val PURPOSE_ACCOUNT_WRAPPED_KEY = "account-wrapped-dek"
    }
}
