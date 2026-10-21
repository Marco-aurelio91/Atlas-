package com.unyx.atlas.chatstorage

import com.unyx.atlas.chatcrypto.ChatCryptoProvider
import com.unyx.atlas.chatcrypto.ChatIdentityKeyPair
import com.unyx.atlas.chatcrypto.ChatSession
import com.unyx.atlas.chatcrypto.DecryptResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Exercises T-10 ("crash após criptografar e antes de enviar") and T-11 ("crash após receber e
 * antes de ACK") for real — through actual JSON serialization and [ChatConversationStore], not
 * just in-memory [ChatSession] state like `core/chat-crypto`'s own tests. "Crash" is simulated by
 * discarding the live [ChatSession] object and reconstructing a fresh one purely from what
 * [ChatConversationStore] has durably stored, via a brand new store instance over the same
 * backing [FakeSharedPreferences] — nothing survives except what actually got persisted.
 */
class ChatConversationStoreTest {

    @Before
    fun setUp() {
        ChatCryptoProvider.install()
    }

    private fun String.utf8() = toByteArray(StandardCharsets.UTF_8)
    private fun ByteArray.asText() = String(this, StandardCharsets.UTF_8)

    private fun pairedSessions(): Pair<ChatSession, ChatSession> {
        val alice = ChatIdentityKeyPair.generate()
        val bob = ChatIdentityKeyPair.generate()
        val aliceSession = ChatSession.establish(
            local = alice, localUserId = "user-alice", localDeviceId = "device-alice",
            remotePublicKeyBytes = bob.publicKeyBytes, remoteUserId = "user-bob", remoteDeviceId = "device-bob",
            conversationId = "conv-1",
        )
        val bobSession = ChatSession.establish(
            local = bob, localUserId = "user-bob", localDeviceId = "device-bob",
            remotePublicKeyBytes = alice.publicKeyBytes, remoteUserId = "user-alice", remoteDeviceId = "device-alice",
            conversationId = "conv-1",
        )
        return aliceSession to bobSession
    }

    @Test
    fun `persistAfterEncrypt stores session and envelope together, both readable after a simulated restart`() {
        val backing = FakeSharedPreferences()
        val (alice, _) = pairedSessions()

        val envelope = alice.encrypt("primeira mensagem".utf8())
        ChatConversationStore(backing).persistAfterEncrypt(alice.snapshot(), envelope)

        // New store instance, same backing prefs — nothing survives except what was committed.
        val reopened = ChatConversationStore(backing)
        val restoredSnapshot = reopened.loadSession("conv-1")
        assertTrue(restoredSnapshot != null)
        assertEquals(1L, restoredSnapshot!!.sendingNextSeq)

        val pending = reopened.pendingOutbox("conv-1")
        assertEquals(1, pending.size)
        assertEquals(envelope, pending.single())
    }

    @Test
    fun `T-10 a session restored after a crash resumes at the right sequence and the pending envelope can still be resent`() {
        val backing = FakeSharedPreferences()
        val (alice, bob) = pairedSessions()
        val store = ChatConversationStore(backing)

        val envelope = alice.encrypt("antes do crash".utf8())
        store.persistAfterEncrypt(alice.snapshot(), envelope)
        // Simulate the crash: the live `alice` object is simply never touched again.

        val aliceAfterRestart = ChatSession.restore(store.loadSession("conv-1")!!)
        val nextEnvelope = aliceAfterRestart.encrypt("depois do restart".utf8())
        // Must continue at sequence 1, not restart from 0 — otherwise it would desync from bob.
        assertEquals(1L, nextEnvelope.sequence)

        // The envelope from before the crash is still exactly what it was — resending it (as a
        // real sync layer would, since it's still in pendingOutbox) decrypts correctly on bob's
        // side, proving no state was lost or corrupted by the crash.
        val resent = store.pendingOutbox("conv-1").single()
        val delivered = bob.decrypt(resent)
        assertTrue(delivered is DecryptResult.Delivered)
        assertEquals("antes do crash", (delivered as DecryptResult.Delivered).plaintext.asText())
    }

    @Test
    fun `T-11 a receive-side crash before ack makes a redelivered envelope a no-op after restart`() {
        val backing = FakeSharedPreferences()
        val (alice, bob) = pairedSessions()
        val store = ChatConversationStore(backing)

        val envelope = alice.encrypt("mensagem recebida".utf8())
        val firstAttempt = bob.decrypt(envelope)
        assertTrue(firstAttempt is DecryptResult.Delivered)
        store.persistAfterReceive(bob.snapshot())
        // Simulate the crash right here — the E2EE receipt was never sent, the live `bob` object
        // is discarded, and a fresh process reloads purely from the store.

        val bobAfterRestart = ChatSession.restore(store.loadSession("conv-1")!!)
        // The sender (or the sync layer's own retry) redelivers the same envelope.
        val redelivered = bobAfterRestart.decrypt(envelope)
        assertEquals(DecryptResult.Duplicate, redelivered)
    }

    @Test
    fun `markSent removes the envelope from the pending outbox`() {
        val backing = FakeSharedPreferences()
        val (alice, _) = pairedSessions()
        val store = ChatConversationStore(backing)

        val envelope = alice.encrypt("mensagem".utf8())
        store.persistAfterEncrypt(alice.snapshot(), envelope)
        assertEquals(1, store.pendingOutbox("conv-1").size)

        store.markSent("conv-1", envelope.sequence)
        assertTrue(store.pendingOutbox("conv-1").isEmpty())
        // The session state itself is untouched by markSent — it's a separate concern.
        assertTrue(store.loadSession("conv-1") != null)
    }

    @Test
    fun `multiple pending envelopes are returned in sequence order regardless of insertion order`() {
        val backing = FakeSharedPreferences()
        val (alice, _) = pairedSessions()
        val store = ChatConversationStore(backing)

        val envelopes = (0 until 5).map { alice.encrypt("msg-$it".utf8()) }
        // Persist out of order on purpose.
        listOf(envelopes[2], envelopes[0], envelopes[4], envelopes[1], envelopes[3]).forEach {
            store.persistAfterEncrypt(alice.snapshot(), it)
        }

        val pending = store.pendingOutbox("conv-1")
        assertEquals(envelopes.map { it.sequence }, pending.map { it.sequence })
    }

    @Test
    fun `deleteConversation removes both the session and every pending outbox entry`() {
        val backing = FakeSharedPreferences()
        val (alice, _) = pairedSessions()
        val store = ChatConversationStore(backing)

        val envelope = alice.encrypt("mensagem".utf8())
        store.persistAfterEncrypt(alice.snapshot(), envelope)

        store.deleteConversation("conv-1")
        assertTrue(store.loadSession("conv-1") == null)
        assertTrue(store.pendingOutbox("conv-1").isEmpty())
    }

    @Test
    fun `loadSession for an unknown conversation returns null instead of throwing`() {
        val store = ChatConversationStore(FakeSharedPreferences())
        assertTrue(store.loadSession("never-heard-of-it") == null)
    }

    @Test
    fun `persistSession stores a freshly established session with no envelope yet`() {
        val backing = FakeSharedPreferences()
        val (alice, bob) = pairedSessions()
        val store = ChatConversationStore(backing)

        // No encrypt()/decrypt() happened yet — this is the state right after establish().
        store.persistSession(alice.snapshot())

        val restored = ChatSession.restore(ChatConversationStore(backing).loadSession("conv-1")!!)
        val envelope = restored.encrypt("primeira mensagem de verdade".utf8())
        assertEquals(0L, envelope.sequence)

        val delivered = bob.decrypt(envelope)
        assertTrue(delivered is DecryptResult.Delivered)
        assertEquals("primeira mensagem de verdade", (delivered as DecryptResult.Delivered).plaintext.asText())
    }

    @Test
    fun `listConversationIds returns every conversation with a stored session`() {
        val backing = FakeSharedPreferences()
        val store = ChatConversationStore(backing)
        val (alice, _) = pairedSessions()

        assertTrue(store.listConversationIds().isEmpty())

        store.persistSession(alice.snapshot())
        assertEquals(listOf("conv-1"), store.listConversationIds())
    }

    @Test
    fun `listConversationIds does not confuse an outbox entry for a conversation id`() {
        val backing = FakeSharedPreferences()
        val store = ChatConversationStore(backing)
        val (alice, _) = pairedSessions()

        val envelope = alice.encrypt("mensagem".utf8())
        store.persistAfterEncrypt(alice.snapshot(), envelope)

        assertEquals(listOf("conv-1"), store.listConversationIds())
    }

    @Test
    fun `markDelivered records a sequence that deliveredSentSequences then returns`() {
        val store = ChatConversationStore(FakeSharedPreferences())

        assertTrue(store.deliveredSentSequences("conv-1").isEmpty())
        store.markDelivered("conv-1", 0L)
        store.markDelivered("conv-1", 2L)

        assertEquals(setOf(0L, 2L), store.deliveredSentSequences("conv-1"))
        // A different conversation's delivered sequences stay empty — not a global set.
        assertTrue(store.deliveredSentSequences("conv-2").isEmpty())
    }

    @Test
    fun `deleteConversation also removes delivered receipts`() {
        val backing = FakeSharedPreferences()
        val (alice, _) = pairedSessions()
        val store = ChatConversationStore(backing)

        val envelope = alice.encrypt("mensagem".utf8())
        store.persistAfterEncrypt(alice.snapshot(), envelope)
        store.markDelivered("conv-1", envelope.sequence)

        store.deleteConversation("conv-1")
        assertTrue(store.deliveredSentSequences("conv-1").isEmpty())
    }
}
