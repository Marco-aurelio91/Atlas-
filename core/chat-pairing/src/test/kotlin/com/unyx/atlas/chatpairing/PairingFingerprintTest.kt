package com.unyx.atlas.chatpairing

import com.unyx.atlas.chatcrypto.ChatCryptoProvider
import com.unyx.atlas.chatcrypto.ChatIdentityKeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

class PairingFingerprintTest {

    @Before
    fun setUp() {
        ChatCryptoProvider.install()
    }

    @Test
    fun `both sides compute the identical fingerprint regardless of who is local vs remote`() {
        val alice = ChatIdentityKeyPair.generate()
        val bob = ChatIdentityKeyPair.generate()

        val fromAlicesPerspective = PairingFingerprint.compute(alice.publicKeyBytes, bob.publicKeyBytes, "conv-1")
        val fromBobsPerspective = PairingFingerprint.compute(bob.publicKeyBytes, alice.publicKeyBytes, "conv-1")

        assertEquals(fromAlicesPerspective, fromBobsPerspective)
    }

    @Test
    fun `a different conversation context yields a different fingerprint for the same two keys`() {
        val alice = ChatIdentityKeyPair.generate()
        val bob = ChatIdentityKeyPair.generate()

        val conv1 = PairingFingerprint.compute(alice.publicKeyBytes, bob.publicKeyBytes, "conv-1")
        val conv2 = PairingFingerprint.compute(alice.publicKeyBytes, bob.publicKeyBytes, "conv-2")

        assertNotEquals(conv1, conv2)
    }

    @Test
    fun `a substituted public key changes the fingerprint`() {
        val alice = ChatIdentityKeyPair.generate()
        val bob = ChatIdentityKeyPair.generate()
        val mallory = ChatIdentityKeyPair.generate()

        val genuine = PairingFingerprint.compute(alice.publicKeyBytes, bob.publicKeyBytes, "conv-1")
        val substituted = PairingFingerprint.compute(alice.publicKeyBytes, mallory.publicKeyBytes, "conv-1")

        assertNotEquals(genuine, substituted)
    }
}
