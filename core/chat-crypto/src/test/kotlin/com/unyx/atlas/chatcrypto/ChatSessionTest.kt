package com.unyx.atlas.chatcrypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Covers the crypto-layer vectors from `ATLAS_CHAT_Plano_Completo_Criacao_e_Implantacao.md`
 * §21.1 that a standalone module (no storage, no network) can actually exercise: T-07 through
 * T-09, plus the sequence-jump bound from T-23. T-10/T-11/T-12 (crash recovery, retry/timeout
 * consistency) are about the *storage and transport* layers this module doesn't have yet —
 * they belong to `chat:storage`/`chat:sync` once those exist, not here; claiming to cover them
 * with a crypto-only test would be misleading (see the plan's own §23: "não marcar 'E2EE
 * pronto' apenas porque uma mensagem cifrada apareceu no banco").
 */
class ChatSessionTest {

    @Before
    fun setUp() {
        ChatCryptoProvider.install()
    }

    private fun establishPair(): Pair<ChatSession, ChatSession> {
        val alice = ChatIdentityKeyPair.generate()
        val bob = ChatIdentityKeyPair.generate()
        val conversationId = "conv-1"
        val aliceSession = ChatSession.establish(
            local = alice,
            localUserId = "user-alice",
            localDeviceId = "device-alice",
            remotePublicKeyBytes = bob.publicKeyBytes,
            remoteUserId = "user-bob",
            remoteDeviceId = "device-bob",
            conversationId = conversationId,
        )
        val bobSession = ChatSession.establish(
            local = bob,
            localUserId = "user-bob",
            localDeviceId = "device-bob",
            remotePublicKeyBytes = alice.publicKeyBytes,
            remoteUserId = "user-alice",
            remoteDeviceId = "device-alice",
            conversationId = conversationId,
        )
        return aliceSession to bobSession
    }

    private fun text(s: String) = s.toByteArray(StandardCharsets.UTF_8)
    private fun String.utf8() = toByteArray(StandardCharsets.UTF_8)
    private fun ByteArray.asText() = String(this, StandardCharsets.UTF_8)

    @Test
    fun `basic round trip decrypts the same plaintext`() {
        val (alice, bob) = establishPair()
        val envelope = alice.encrypt(text("oi, tudo bem?"))
        val result = bob.decrypt(envelope)
        assertTrue(result is DecryptResult.Delivered)
        assertEquals("oi, tudo bem?", (result as DecryptResult.Delivered).plaintext.asText())
    }

    @Test
    fun `both directions carry independent sequences and decrypt correctly`() {
        val (alice, bob) = establishPair()
        val toBob = alice.encrypt("alice para bob".utf8())
        val toAlice = bob.encrypt("bob para alice".utf8())

        val atBob = bob.decrypt(toBob) as DecryptResult.Delivered
        val atAlice = alice.decrypt(toAlice) as DecryptResult.Delivered
        assertEquals("alice para bob", atBob.plaintext.asText())
        assertEquals("bob para alice", atAlice.plaintext.asText())
    }

    @Test
    fun `T-07 same envelope delivered twice is recognized as a duplicate, not shown again`() {
        val (alice, bob) = establishPair()
        val envelope = alice.encrypt(text("mensagem unica"))

        val first = bob.decrypt(envelope)
        assertTrue(first is DecryptResult.Delivered)

        val second = bob.decrypt(envelope)
        assertEquals(DecryptResult.Duplicate, second)

        val third = bob.decrypt(envelope)
        assertEquals(DecryptResult.Duplicate, third)
    }

    @Test
    fun `T-08 messages arriving out of order all decrypt to their original plaintext`() {
        val (alice, bob) = establishPair()
        val sent = listOf("um", "dois", "tres", "quatro", "cinco").map { alice.encrypt(it.utf8()) }

        // Delivered out of order: 2, 0, 4, 1, 3 — nothing arrives in sequence.
        val deliveryOrder = listOf(2, 0, 4, 1, 3)
        val decrypted = deliveryOrder.associateWith { index -> bob.decrypt(sent[index]) }

        deliveryOrder.forEach { index ->
            val result = decrypted.getValue(index)
            assertTrue("sequence $index should have delivered", result is DecryptResult.Delivered)
            assertEquals(sent[index].sequence, index.toLong())
        }
        assertEquals("tres", (decrypted.getValue(2) as DecryptResult.Delivered).plaintext.asText())
        assertEquals("um", (decrypted.getValue(0) as DecryptResult.Delivered).plaintext.asText())
        assertEquals("cinco", (decrypted.getValue(4) as DecryptResult.Delivered).plaintext.asText())
        assertEquals("dois", (decrypted.getValue(1) as DecryptResult.Delivered).plaintext.asText())
        assertEquals("quatro", (decrypted.getValue(3) as DecryptResult.Delivered).plaintext.asText())

        // Re-delivering an out-of-order one that already decrypted is still a duplicate, not a
        // second visible message.
        assertEquals(DecryptResult.Duplicate, bob.decrypt(sent[2]))
    }

    @Test
    fun `T-09 tampered ciphertext is rejected without corrupting the session for later messages`() {
        val (alice, bob) = establishPair()
        val envelope = alice.encrypt(text("conteudo original"))
        val tamperedBytes = envelope.ciphertextAndTag.copyOf()
        tamperedBytes[0] = (tamperedBytes[0].toInt() xor 0xFF).toByte()
        val tampered = envelope.copy(ciphertextAndTag = tamperedBytes)

        val result = bob.decrypt(tampered)
        assertTrue(result is DecryptResult.Rejected)

        // The session must still work for the next legitimate message — rejecting one tampered
        // envelope must not have corrupted the ratchet.
        val next = alice.encrypt(text("mensagem seguinte, legitima"))
        val nextResult = bob.decrypt(next)
        assertTrue(nextResult is DecryptResult.Delivered)
        assertEquals("mensagem seguinte, legitima", (nextResult as DecryptResult.Delivered).plaintext.asText())
    }

    @Test
    fun `T-09 an envelope re-tagged with a different sequence fails authentication`() {
        // The sequence number is authenticated (associated data), not just a label — relabeling a
        // genuine envelope to a different slot must not let it decrypt.
        val (alice, bob) = establishPair()
        alice.encrypt(text("mensagem 0")) // sequence 0, discarded
        val envelope1 = alice.encrypt(text("mensagem 1")) // sequence 1
        val relabeled = envelope1.copy(sequence = 0)

        val result = bob.decrypt(relabeled)
        assertTrue(result is DecryptResult.Rejected)
    }

    @Test
    fun `T-09 an envelope claiming a different sender device fails authentication`() {
        val (alice, bob) = establishPair()
        val envelope = alice.encrypt(text("mensagem"))
        val spoofed = envelope.copy(senderDeviceId = "device-mallory")

        // The session itself refuses to even attempt decryption against a sender id it wasn't
        // established for.
        assertThrows(IllegalArgumentException::class.java) { bob.decrypt(spoofed) }
    }

    @Test
    fun `T-23 a sequence jump far beyond the skipped-key window is rejected, not derived forever`() {
        val (alice, bob) = establishPair()
        // Advance alice's chain far past bob's skip window without bob ever seeing those messages.
        repeat(2000) { alice.encrypt(text("preenchendo")) }
        val farAhead = alice.encrypt(text("mensagem muito a frente"))

        assertThrows(IllegalArgumentException::class.java) { bob.decrypt(farAhead) }
    }

    @Test
    fun `message keys are never reused across messages`() {
        val (alice, bob) = establishPair()
        val first = alice.encrypt(text("mesmo texto"))
        val second = alice.encrypt(text("mesmo texto"))
        // Same plaintext, same sender/conversation — ciphertext must still differ because the
        // ratchet produced a fresh key (and a fresh nonce) for each message.
        assertFalse(first.ciphertextAndTag.contentEquals(second.ciphertextAndTag))
        assertNotEquals(first.sequence, second.sequence)
    }

    @Test
    fun `identity fingerprint is stable for the same key and differs across identities`() {
        val alice = ChatIdentityKeyPair.generate()
        val bob = ChatIdentityKeyPair.generate()
        assertEquals(alice.publicKeyFingerprint(), ChatIdentityKeyPair.fingerprintOf(alice.publicKeyBytes))
        assertNotEquals(alice.publicKeyFingerprint(), bob.publicKeyFingerprint())
    }

    @Test
    fun `a session restored from a snapshot continues the conversation correctly`() {
        val (alice, bob) = establishPair()
        alice.encrypt(text("antes do reinicio")) // sequence 0, bob never sees it (simulates a crash)
        val afterRestart = ChatSession.restore(alice.snapshot())

        // The restored session must pick up exactly at sequence 1, not restart from 0 — resending
        // sequence 0 again would desync it from bob's own receiving chain forever.
        val envelope = afterRestart.encrypt(text("depois do reinicio"))
        assertEquals(1L, envelope.sequence)

        val result = bob.decrypt(envelope)
        assertTrue(result is DecryptResult.Delivered)
        assertEquals("depois do reinicio", (result as DecryptResult.Delivered).plaintext.asText())
    }

    @Test
    fun `T-11-style idempotent reprocessing survives a simulated restart`() {
        val (alice, bob) = establishPair()
        val envelope = alice.encrypt(text("mensagem a processar"))

        val firstAttempt = bob.decrypt(envelope)
        assertTrue(firstAttempt is DecryptResult.Delivered)

        // Simulate a crash between delivering the message and acking it: bob's process dies and
        // restarts from the last persisted snapshot, which already reflects this message as
        // processed (highestProcessedSeq) because the snapshot is taken *after* a successful
        // decrypt — see ChatSessionStore's doc for why that ordering is what makes this true.
        val bobAfterRestart = ChatSession.restore(bob.snapshot())

        // The network (or the sender's own retry) redelivers the same envelope — reprocessing
        // must be a no-op, not a second visible message.
        val redelivered = bobAfterRestart.decrypt(envelope)
        assertEquals(DecryptResult.Duplicate, redelivered)
    }

    @Test
    fun `X25519 agreement is symmetric between both sides`() {
        val alice = ChatIdentityKeyPair.generate()
        val bob = ChatIdentityKeyPair.generate()
        val fromAlice = alice.agree(bob.publicKeyBytes)
        val fromBob = bob.agree(alice.publicKeyBytes)
        assertArrayEquals(fromAlice, fromBob)
    }

    @Test
    fun `an identity restored from its encoded bytes produces the exact same shared secret`() {
        val alice = ChatIdentityKeyPair.generate()
        val bob = ChatIdentityKeyPair.generate()

        val aliceRestored = ChatIdentityKeyPair.restore(alice.privateKeyBytes(), alice.publicKeyBytes)
        assertArrayEquals(alice.agree(bob.publicKeyBytes), aliceRestored.agree(bob.publicKeyBytes))
    }

    @Test
    fun `a session established with a restored identity can still exchange real messages`() {
        val alice = ChatIdentityKeyPair.generate()
        val bob = ChatIdentityKeyPair.generate()
        // Simulates the app restart case: alice's in-memory identity is discarded and rebuilt
        // purely from what `chat:storage`'s ChatIdentityStore would have persisted.
        val aliceRestored = ChatIdentityKeyPair.restore(alice.privateKeyBytes(), alice.publicKeyBytes)

        val aliceSession = ChatSession.establish(
            local = aliceRestored, localUserId = "user-alice", localDeviceId = "device-alice",
            remotePublicKeyBytes = bob.publicKeyBytes, remoteUserId = "user-bob", remoteDeviceId = "device-bob",
            conversationId = "conv-1",
        )
        val bobSession = ChatSession.establish(
            local = bob, localUserId = "user-bob", localDeviceId = "device-bob",
            remotePublicKeyBytes = alice.publicKeyBytes, remoteUserId = "user-alice", remoteDeviceId = "device-alice",
            conversationId = "conv-1",
        )

        val envelope = aliceSession.encrypt(text("mensagem com identidade restaurada"))
        val result = bobSession.decrypt(envelope)
        assertTrue(result is DecryptResult.Delivered)
        assertEquals("mensagem com identidade restaurada", (result as DecryptResult.Delivered).plaintext.asText())
    }
}
