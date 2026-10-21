package com.unyx.atlas.chatstorage

import com.unyx.atlas.chatcrypto.ChatCryptoProvider
import com.unyx.atlas.chatcrypto.ChatIdentityKeyPair
import com.unyx.atlas.chatcrypto.ChatSession
import com.unyx.atlas.chatcrypto.DecryptResult
import com.unyx.atlas.chatcrypto.agree
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Exercises the device's own chat identity surviving a restart — the one piece of state this
 * whole feature cannot afford to lose silently (unlike per-conversation ratchet state, there is
 * no "just resend it" recovery if this is gone: every existing pairing would need to be redone).
 */
class ChatIdentityStoreTest {

    @Before
    fun setUp() {
        ChatCryptoProvider.install()
    }

    private fun String.utf8() = toByteArray(StandardCharsets.UTF_8)
    private fun ByteArray.asText() = String(this, StandardCharsets.UTF_8)

    @Test
    fun `load on an empty store returns null instead of throwing`() {
        assertNull(ChatIdentityStore(FakeSharedPreferences()).load())
    }

    @Test
    fun `save then load round trips to an identity that agrees identically with a peer`() {
        val backing = FakeSharedPreferences()
        val original = ChatIdentityKeyPair.generate()
        val peer = ChatIdentityKeyPair.generate()

        ChatIdentityStore(backing).save(original)
        val restored = ChatIdentityStore(backing).load()

        assertNotNull(restored)
        assertArrayEquals(original.publicKeyBytes, restored!!.publicKeyBytes)
        assertArrayEquals(original.agree(peer.publicKeyBytes), restored.agree(peer.publicKeyBytes))
    }

    @Test
    fun `a restored identity can establish a session that exchanges real messages`() {
        val backing = FakeSharedPreferences()
        val alice = ChatIdentityKeyPair.generate()
        val bob = ChatIdentityKeyPair.generate()
        ChatIdentityStore(backing).save(alice)

        // Simulates a full app restart: nothing survives except what the store persisted.
        val aliceAfterRestart = ChatIdentityStore(backing).load()!!
        val aliceSession = ChatSession.establish(
            local = aliceAfterRestart, localUserId = "user-alice", localDeviceId = "device-alice",
            remotePublicKeyBytes = bob.publicKeyBytes, remoteUserId = "user-bob", remoteDeviceId = "device-bob",
            conversationId = "conv-1",
        )
        val bobSession = ChatSession.establish(
            local = bob, localUserId = "user-bob", localDeviceId = "device-bob",
            remotePublicKeyBytes = alice.publicKeyBytes, remoteUserId = "user-alice", remoteDeviceId = "device-alice",
            conversationId = "conv-1",
        )

        val envelope = aliceSession.encrypt("ola apos reinicio".utf8())
        val result = bobSession.decrypt(envelope)
        assertTrue(result is DecryptResult.Delivered)
        assertEquals("ola apos reinicio", (result as DecryptResult.Delivered).plaintext.asText())
    }

    @Test
    fun `loadOrCreate persists a freshly generated identity so the next load sees the same one`() {
        val backing = FakeSharedPreferences()
        val store = ChatIdentityStore(backing)

        val created = store.loadOrCreate()
        val loadedAfter = ChatIdentityStore(backing).load()

        assertNotNull(loadedAfter)
        assertArrayEquals(created.publicKeyBytes, loadedAfter!!.publicKeyBytes)
    }

    @Test
    fun `loadOrCreate does not overwrite an identity that already exists`() {
        val backing = FakeSharedPreferences()
        val store = ChatIdentityStore(backing)
        val original = store.loadOrCreate()

        val second = ChatIdentityStore(backing).loadOrCreate()

        assertArrayEquals(original.publicKeyBytes, second.publicKeyBytes)
    }
}
