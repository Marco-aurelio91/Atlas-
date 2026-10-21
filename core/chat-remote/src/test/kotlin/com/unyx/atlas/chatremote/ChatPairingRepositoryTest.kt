package com.unyx.atlas.chatremote

import com.unyx.atlas.chatcrypto.ChatCryptoProvider
import com.unyx.atlas.chatcrypto.DecryptResult
import com.unyx.atlas.chatstorage.ChatContactStore
import com.unyx.atlas.chatstorage.ChatConversationStore
import com.unyx.atlas.chatstorage.ChatIdentityStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Exercises [ChatPairingRepository] through the full bilateral handshake from
 * `docs/ATLAS_CHAT_Plano_Completo_Criacao_e_Implantacao.md` §8.3, with a real
 * [FakeChatPairingRemoteClient] standing in for the `atlas_chat_*` backend — this is the first
 * test in the whole `core/chat-*` tree that exercises crypto + storage + pairing + remote
 * together through the orchestration layer a screen would actually call, rather than wiring the
 * four pieces together by hand inside the test itself (see `core/chat-pairing`'s own
 * `PairingEndToEndTest` for that lower-level version).
 */
class ChatPairingRepositoryTest {

    @Before
    fun setUp() {
        ChatCryptoProvider.install()
    }

    private fun String.utf8() = toByteArray(StandardCharsets.UTF_8)
    private fun ByteArray.asText() = String(this, StandardCharsets.UTF_8)

    private fun repositoryFor(userId: String, remote: FakeChatPairingRemoteClient): ChatPairingRepository =
        ChatPairingRepository(
            identityStore = ChatIdentityStore(FakeSharedPreferences()),
            sessionStore = ChatConversationStore(FakeSharedPreferences()),
            contactStore = ChatContactStore(FakeSharedPreferences()),
            remote = remote,
            auth = FakeChatAuthSession(userId, accessToken = userId),
        )

    @Test
    fun `full pairing flow establishes matching sessions that exchange real encrypted messages`() = runTest {
        val remote = FakeChatPairingRemoteClient()
        val alice = repositoryFor("user-alice", remote)
        val bob = repositoryFor("user-bob", remote)

        val invitation = alice.createInvite(displayName = "Alice", nowEpochSeconds = 1_000_000L)
        val cardBytes = invitation.serialize() // <- what actually crosses over NFC/QR

        val accepted = bob.acceptInvite(cardBytes, nowEpochSeconds = 1_000_000L + 5)

        // A hasn't confirmed yet — B's one-shot check must say so, not block or guess.
        assertNull(bob.waitForConfirmation(accepted, contactDisplayName = accepted.invitation.displayName))

        val claim = alice.fetchClaim(invitation)
        assertNotNull(claim)
        // Both sides must land on the exact same presencial fingerprint — otherwise comparing it
        // out loud would never agree for a correct pairing.
        assertEquals(accepted.fingerprint, claim!!.fingerprint)

        val aliceSession = alice.confirmAndEstablish(invitation, claim, contactDisplayName = "Bob")
        val bobSession = bob.waitForConfirmation(accepted, contactDisplayName = accepted.invitation.displayName)
        assertNotNull(bobSession)

        val envelope = aliceSession.encrypt("ola bob, pareamento concluido!".utf8())
        val result = bobSession!!.decrypt(envelope)
        assertTrue(result is DecryptResult.Delivered)
        assertEquals("ola bob, pareamento concluido!", (result as DecryptResult.Delivered).plaintext.asText())
    }

    @Test
    fun `confirming a pairing saves a local contact record for both sides`() = runTest {
        val remote = FakeChatPairingRemoteClient()
        val aliceContacts = ChatContactStore(FakeSharedPreferences())
        val bobContacts = ChatContactStore(FakeSharedPreferences())
        val alice = ChatPairingRepository(
            identityStore = ChatIdentityStore(FakeSharedPreferences()), sessionStore = ChatConversationStore(FakeSharedPreferences()),
            contactStore = aliceContacts, remote = remote, auth = FakeChatAuthSession("user-alice", "user-alice"),
        )
        val bob = ChatPairingRepository(
            identityStore = ChatIdentityStore(FakeSharedPreferences()), sessionStore = ChatConversationStore(FakeSharedPreferences()),
            contactStore = bobContacts, remote = remote, auth = FakeChatAuthSession("user-bob", "user-bob"),
        )

        val invitation = alice.createInvite(displayName = "Alice", nowEpochSeconds = 1_000_000L)
        val accepted = bob.acceptInvite(invitation.serialize(), nowEpochSeconds = 1_000_000L + 5)
        val claim = alice.fetchClaim(invitation)!!

        alice.confirmAndEstablish(invitation, claim, contactDisplayName = "Bob (do trabalho)")
        bob.waitForConfirmation(accepted, contactDisplayName = accepted.invitation.displayName)

        val aliceContact = aliceContacts.load(invitation.invitationId)
        assertNotNull(aliceContact)
        assertEquals("Bob (do trabalho)", aliceContact!!.displayName)
        assertEquals("user-bob", aliceContact.remoteUserId)

        val bobContact = bobContacts.load(invitation.invitationId)
        assertNotNull(bobContact)
        // B's default is A's own self-declared name from the card — confirms the prefill wiring,
        // not just that *some* name got saved.
        assertEquals("Alice", bobContact!!.displayName)
        assertEquals("user-alice", bobContact.remoteUserId)

        // Both sides persisted the SAME presencial safety-number fingerprint (plan §16's
        // "Identidade verificada") — symmetric by construction, see PairingFingerprint's own doc.
        assertTrue(aliceContact.verifiedFingerprint.isNotBlank())
        assertEquals(aliceContact.verifiedFingerprint, bobContact.verifiedFingerprint)
    }

    @Test
    fun `a second concurrent claim on the same invite fails (T-04)`() = runTest {
        val remote = FakeChatPairingRemoteClient()
        val alice = repositoryFor("user-alice", remote)
        val bob = repositoryFor("user-bob", remote)
        val carol = repositoryFor("user-carol", remote)

        val invitation = alice.createInvite(displayName = "Alice", nowEpochSeconds = 1_000_000L)
        val cardBytes = invitation.serialize()

        bob.acceptInvite(cardBytes, nowEpochSeconds = 1_000_000L + 1)

        val secondClaim = runCatching { carol.acceptInvite(cardBytes, nowEpochSeconds = 1_000_000L + 2) }
        assertTrue(secondClaim.exceptionOrNull() is ChatPairingException)
    }

    @Test
    fun `an expired invite is rejected locally before any network call`() = runTest {
        val remote = FakeChatPairingRemoteClient()
        val alice = repositoryFor("user-alice", remote)
        val bob = repositoryFor("user-bob", remote)

        val invitation = alice.createInvite(displayName = "Alice", nowEpochSeconds = 1_000_000L)
        val cardBytes = invitation.serialize()
        val farInTheFuture = invitation.expiresAtEpochSeconds + 1

        val attempt = runCatching { bob.acceptInvite(cardBytes, nowEpochSeconds = farInTheFuture) }
        assertTrue(attempt.exceptionOrNull() is com.unyx.atlas.chatpairing.InvalidPairingCardException)
    }

    @Test
    fun `fetchClaim returns null while the invite is still pending`() = runTest {
        val remote = FakeChatPairingRemoteClient()
        val alice = repositoryFor("user-alice", remote)

        val invitation = alice.createInvite(displayName = "Alice", nowEpochSeconds = 1_000_000L)

        assertNull(alice.fetchClaim(invitation))
    }

    @Test
    fun `the device id derived from an identity is stable across repository instances`() = runTest {
        val remote = FakeChatPairingRemoteClient()
        val backingIdentity = FakeSharedPreferences()
        val repoA = ChatPairingRepository(
            identityStore = ChatIdentityStore(backingIdentity),
            sessionStore = ChatConversationStore(FakeSharedPreferences()),
            contactStore = ChatContactStore(FakeSharedPreferences()),
            remote = remote,
            auth = FakeChatAuthSession("user-alice", "user-alice"),
        )
        val identity = repoA.ensureDeviceRegistered()

        val repoB = ChatPairingRepository(
            identityStore = ChatIdentityStore(backingIdentity),
            sessionStore = ChatConversationStore(FakeSharedPreferences()),
            contactStore = ChatContactStore(FakeSharedPreferences()),
            remote = remote,
            auth = FakeChatAuthSession("user-alice", "user-alice"),
        )
        assertEquals(repoA.deviceIdFor(identity), repoB.deviceIdFor(identity))
    }
}
