package com.unyx.atlas.security

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AtlasSecurityTest {
    private val security = AtlasSecurity()

    @Test
    fun accountEnvelopeRoundTripsAndDoesNotContainPlaintext() {
        val rootKey = ByteArray(32).also(SecureRandom()::nextBytes)
        val plaintext = "memória pessoal e confidencial".toByteArray()
        val context = CryptoContext("user-123", "object-456", "memory")
        try {
            val envelope = security.encryptForAccount(plaintext, context, "key-789", rootKey)
            val restored = security.decryptForAccount(envelope, context, "key-789", rootKey)
            try {
                assertArrayEquals(plaintext, restored)
                assertFalse(envelope.toJson().toString().contains(String(plaintext)))
                assertTrue(EncryptedEnvelope.parse(envelope.toJson().toString()) == envelope)
            } finally {
                Arrays.fill(restored, 0)
            }
        } finally {
            Arrays.fill(rootKey, 0)
            Arrays.fill(plaintext, 0)
        }
    }

    @Test
    fun accountEnvelopeRejectsDifferentUserObjectTypeAndRootKey() {
        val rootKey = ByteArray(32).also(SecureRandom()::nextBytes)
        val otherKey = ByteArray(32).also(SecureRandom()::nextBytes)
        val plaintext = "private".toByteArray()
        val context = CryptoContext("user-a", "object-a", "note")
        try {
            val envelope = security.encryptForAccount(plaintext, context, "key-a", rootKey)
            assertThrows(GeneralSecurityException::class.java) {
                security.decryptForAccount(envelope, context.copy(userId = "user-b"), "key-a", rootKey)
            }
            assertThrows(GeneralSecurityException::class.java) {
                security.decryptForAccount(envelope, context.copy(objectId = "object-b"), "key-a", rootKey)
            }
            assertThrows(GeneralSecurityException::class.java) {
                security.decryptForAccount(envelope, context.copy(objectType = "attachment"), "key-a", rootKey)
            }
            assertThrows(GeneralSecurityException::class.java) {
                security.decryptForAccount(envelope, context, "key-a", otherKey)
            }
        } finally {
            Arrays.fill(rootKey, 0)
            Arrays.fill(otherKey, 0)
            Arrays.fill(plaintext, 0)
        }
    }

    @Test
    fun accountEnvelopeRejectsCiphertextTampering() {
        val rootKey = ByteArray(32).also(SecureRandom()::nextBytes)
        val plaintext = "private".toByteArray()
        val context = CryptoContext("user-a", "object-a", "note")
        try {
            val envelope = security.encryptForAccount(plaintext, context, "key-a", rootKey)
            val ciphertext = Base64.getDecoder().decode(envelope.ciphertextB64)
            ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
            val tampered = envelope.copy(ciphertextB64 = Base64.getEncoder().encodeToString(ciphertext))
            assertThrows(GeneralSecurityException::class.java) {
                security.decryptForAccount(tampered, context, "key-a", rootKey)
            }
        } finally {
            Arrays.fill(rootKey, 0)
            Arrays.fill(plaintext, 0)
        }
    }

    @Test
    fun recoveryCodeRoundTripsAndRejectsWrongCodeOrAccount() {
        val (code, codeBytes) = AtlasRecoveryEnvelopeCrypto.newRecoveryCode()
        val rootKey = ByteArray(32).also(SecureRandom()::nextBytes)
        try {
            val envelope = AtlasRecoveryEnvelopeCrypto.wrapAccountRootKey(rootKey, codeBytes, "user-a", "key-a")
            val parsedCode = AtlasRecoveryEnvelopeCrypto.parseRecoveryCode(code)
            val restored = AtlasRecoveryEnvelopeCrypto.unwrapAccountRootKey(envelope, parsedCode, "user-a")
            try {
                assertArrayEquals(rootKey, restored)
                assertFalse(envelope.toJson().toString().contains(code.replace(" ", "")))
            } finally {
                Arrays.fill(parsedCode, 0)
                Arrays.fill(restored, 0)
            }

            val wrongCode = ByteArray(32).also(SecureRandom()::nextBytes)
            try {
                assertThrows(GeneralSecurityException::class.java) {
                    AtlasRecoveryEnvelopeCrypto.unwrapAccountRootKey(envelope, wrongCode, "user-a")
                }
            } finally {
                Arrays.fill(wrongCode, 0)
            }
            assertThrows(GeneralSecurityException::class.java) {
                AtlasRecoveryEnvelopeCrypto.unwrapAccountRootKey(envelope, codeBytes, "user-b")
            }
        } finally {
            Arrays.fill(codeBytes, 0)
            Arrays.fill(rootKey, 0)
        }
    }
}
