package com.unyx.atlas.chatremote

import com.unyx.atlas.chatcrypto.ChatCryptoProvider
import com.unyx.atlas.chatcrypto.ChatIdentityKeyPair
import com.unyx.atlas.chatcrypto.ChatSession
import com.unyx.atlas.chatstorage.ChatContact
import com.unyx.atlas.chatstorage.ChatContactStore
import com.unyx.atlas.chatstorage.ChatConversationStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises [ChatMessagingRepository] — the `chat:sync` layer on top of the already-established
 * sessions `core/chat-pairing`'s own tests (and [ChatPairingRepositoryTest]) build — against a
 * [FakeChatMessageRemoteClient] standing in for the `atlas_chat_{send,fetch,ack}_envelope` RPCs.
 */
class ChatMessagingRepositoryTest {

    @Before
    fun setUp() {
        ChatCryptoProvider.install()
    }

    private data class MessagingPair(
        val alice: ChatMessagingRepository,
        val bob: ChatMessagingRepository,
        val aliceStore: ChatConversationStore,
        val bobStore: ChatConversationStore,
        val aliceContacts: ChatContactStore,
        val bobContacts: ChatContactStore,
        val aliceAvatars: FakeChatAvatarStore,
        val bobAvatars: FakeChatAvatarStore,
    )

    /** Each side gets its OWN [ChatConversationStore]/[ChatContactStore] — exactly like
     *  production, where alice's phone and bob's phone each hold their own local, encrypted copy
     *  of the session under the same conversationId key. A single shared store for both sides (an
     *  earlier, buggy version of this test) silently clobbers one side's snapshot with the
     *  other's: both `persistSession` calls write to the identical "session:$conversationId" key,
     *  so the second call overwrites the first and both repositories end up sharing one side's
     *  chain keys — completely different from a real two-device pairing and the reason every test
     *  here failed until this was caught. A minimal [ChatContact] row is seeded on both sides so
     *  [ChatContactStore.applyReceivedProfile] has something to attach a received profile update
     *  to — in production that row always exists by the time messaging is possible, since pairing
     *  (`ChatPairingRepository`) always creates it first. */
    private fun establishedPair(
        remote: FakeChatMessageRemoteClient,
        conversationId: String = "conv-1",
    ): MessagingPair {
        val aliceIdentity = ChatIdentityKeyPair.generate()
        val bobIdentity = ChatIdentityKeyPair.generate()
        val aliceSession = ChatSession.establish(
            local = aliceIdentity, localUserId = "user-alice", localDeviceId = "device-alice",
            remotePublicKeyBytes = bobIdentity.publicKeyBytes, remoteUserId = "user-bob", remoteDeviceId = "device-bob",
            conversationId = conversationId,
        )
        val bobSession = ChatSession.establish(
            local = bobIdentity, localUserId = "user-bob", localDeviceId = "device-bob",
            remotePublicKeyBytes = aliceIdentity.publicKeyBytes, remoteUserId = "user-alice", remoteDeviceId = "device-alice",
            conversationId = conversationId,
        )
        val aliceStore = ChatConversationStore(FakeSharedPreferences())
        val bobStore = ChatConversationStore(FakeSharedPreferences())
        aliceStore.persistSession(aliceSession.snapshot())
        bobStore.persistSession(bobSession.snapshot())

        val aliceContacts = ChatContactStore(FakeSharedPreferences())
        val bobContacts = ChatContactStore(FakeSharedPreferences())
        aliceContacts.save(ChatContact(conversationId, "Bob", "user-bob", "device-bob", 1_000_000L, "fp"))
        bobContacts.save(ChatContact(conversationId, "Alice", "user-alice", "device-alice", 1_000_000L, "fp"))

        val aliceAvatars = FakeChatAvatarStore()
        val bobAvatars = FakeChatAvatarStore()

        remote.registerConversation(conversationId, deviceA = "device-alice", deviceB = "device-bob")
        val aliceRepo = ChatMessagingRepository(aliceStore, aliceContacts, aliceAvatars, remote, FakeChatAuthSession("user-alice", "device-alice"))
        val bobRepo = ChatMessagingRepository(bobStore, bobContacts, bobAvatars, remote, FakeChatAuthSession("user-bob", "device-bob"))
        return MessagingPair(aliceRepo, bobRepo, aliceStore, bobStore, aliceContacts, bobContacts, aliceAvatars, bobAvatars)
    }

    @Test
    fun `a sent message is received, decrypted, and acked off the server`() = runTest {
        val remote = FakeChatMessageRemoteClient()
        val (alice, bob) = establishedPair(remote)

        alice.sendText("conv-1", "oi bob!")
        val incoming = bob.fetchAndDecryptNew()

        assertEquals(1, incoming.size)
        assertEquals("oi bob!", incoming.single().text)
        // Acked means gone from the server-side queue — fetching again returns nothing new (the
        // E2EE receipt bob's fetch just sent back went to alice, not to bob).
        assertTrue(bob.fetchAndDecryptNew().isEmpty())
    }

    @Test
    fun `a network failure during send leaves the envelope in the local outbox for resendPending`() = runTest {
        val remote = FakeChatMessageRemoteClient()
        val pair = establishedPair(remote)
        val (alice, bob, aliceStore) = pair

        remote.failNextSendFor = "conv-1" to "device-alice"
        // send() itself does not throw for a transport failure — the local encrypt+persist half
        // (what the caller actually needs the sequence number from, to record the message
        // immediately) always completes; only the network leg was best-effort here.
        val envelope = alice.sendText("conv-1", "primeira tentativa")
        assertEquals(0L, envelope.sequence)
        // Durable locally even though the network call failed.
        assertEquals(1, aliceStore.pendingOutbox("conv-1").size)
        assertTrue(bob.fetchAndDecryptNew().isEmpty())

        alice.resendPending("conv-1")
        val incoming = bob.fetchAndDecryptNew()
        assertEquals(1, incoming.size)
        assertEquals("primeira tentativa", incoming.single().text)
    }

    @Test
    fun `resendPending after a successful send is a no-op, not a duplicate`() = runTest {
        val remote = FakeChatMessageRemoteClient()
        val (alice, bob) = establishedPair(remote)

        alice.sendText("conv-1", "mensagem unica")
        alice.resendPending("conv-1") // already sent+marked — pendingOutbox is empty, nothing to resend

        val incoming = bob.fetchAndDecryptNew()
        assertEquals(1, incoming.size)
    }

    @Test
    fun `messages across multiple conversations are grouped and decrypted independently`() = runTest {
        val remote = FakeChatMessageRemoteClient()
        val (alice1, bob1) = establishedPair(remote, conversationId = "conv-1")

        // A second, independent pairing between two different identities under the same bob
        // access-token/device for simplicity — what matters is that fetchAndDecryptNew groups by
        // conversation_id and doesn't cross-decrypt with the wrong session.
        val carolIdentity = ChatIdentityKeyPair.generate()
        val daveIdentity = ChatIdentityKeyPair.generate()
        val carolSession = ChatSession.establish(
            local = carolIdentity, localUserId = "user-carol", localDeviceId = "device-carol",
            remotePublicKeyBytes = daveIdentity.publicKeyBytes, remoteUserId = "user-dave", remoteDeviceId = "device-dave",
            conversationId = "conv-2",
        )
        val daveSession = ChatSession.establish(
            local = daveIdentity, localUserId = "user-dave", localDeviceId = "device-dave",
            remotePublicKeyBytes = carolIdentity.publicKeyBytes, remoteUserId = "user-carol", remoteDeviceId = "device-carol",
            conversationId = "conv-2",
        )
        val carolStore = ChatConversationStore(FakeSharedPreferences())
        val daveStore = ChatConversationStore(FakeSharedPreferences())
        carolStore.persistSession(carolSession.snapshot())
        daveStore.persistSession(daveSession.snapshot())
        remote.registerConversation("conv-2", deviceA = "device-carol", deviceB = "device-dave")
        val carolRepo = ChatMessagingRepository(
            carolStore, ChatContactStore(FakeSharedPreferences()), FakeChatAvatarStore(), remote, FakeChatAuthSession("user-carol", "device-carol"),
        )
        val daveRepo = ChatMessagingRepository(
            daveStore, ChatContactStore(FakeSharedPreferences()), FakeChatAvatarStore(), remote, FakeChatAuthSession("user-dave", "device-dave"),
        )

        alice1.sendText("conv-1", "de alice pra bob")
        carolRepo.sendText("conv-2", "de carol pra dave")

        val bobIncoming = bob1.fetchAndDecryptNew()
        val daveIncoming = daveRepo.fetchAndDecryptNew()
        assertEquals(1, bobIncoming.size)
        assertEquals("de alice pra bob", bobIncoming.single().text)
        assertEquals(1, daveIncoming.size)
        assertEquals("de carol pra dave", daveIncoming.single().text)
    }

    @Test
    fun `a text message triggers an automatic E2EE delivery receipt back to the sender`() = runTest {
        val remote = FakeChatMessageRemoteClient()
        val pair = establishedPair(remote)
        val (alice, bob, aliceStore) = pair

        val envelope = alice.sendText("conv-1", "oi bob!")
        assertTrue(aliceStore.deliveredSentSequences("conv-1").isEmpty()) // not yet — bob hasn't processed it

        val bobIncoming = bob.fetchAndDecryptNew() // decrypts the text AND sends the receipt back
        assertEquals(1, bobIncoming.size)

        // Alice has to actually fetch to see the receipt — it's its own envelope on the wire,
        // not a side effect that teleports into her store.
        assertTrue(aliceStore.deliveredSentSequences("conv-1").isEmpty())
        val aliceIncoming = alice.fetchAndDecryptNew()
        // The receipt never surfaces as a visible message — only as delivery state.
        assertTrue(aliceIncoming.isEmpty())
        assertEquals(setOf(envelope.sequence), aliceStore.deliveredSentSequences("conv-1"))
    }

    @Test
    fun `a delivery receipt does not itself trigger another receipt`() = runTest {
        val remote = FakeChatMessageRemoteClient()
        val (alice, bob) = establishedPair(remote)

        alice.sendText("conv-1", "oi bob!")
        bob.fetchAndDecryptNew() // decrypts + sends one receipt to alice
        alice.fetchAndDecryptNew() // processes that receipt

        // If processing a receipt wrongly produced a receipt-of-receipt, it would be sitting in
        // bob's queue now. "Recibos não geram outros recibos E2EE, evitando ciclos" (plano §12.3).
        assertTrue(bob.fetchAndDecryptNew().isEmpty())
    }

    @Test
    fun `a profile update is applied to the local contact and never surfaces as a message`() = runTest {
        val remote = FakeChatMessageRemoteClient()
        val pair = establishedPair(remote)
        val (alice, bob, _, _, _, bobContacts) = pair

        alice.sendProfileUpdate("conv-1", displayName = "Alice (trabalho)", statusMessage = "Reunião até 15h")
        val incoming = bob.fetchAndDecryptNew()

        // Not a timeline message — plan §5.1: "Não são mensagens visíveis na linha do tempo".
        assertTrue(incoming.isEmpty())
        val bobsViewOfAlice = bobContacts.load("conv-1")
        assertEquals("Alice (trabalho)", bobsViewOfAlice?.receivedDisplayName)
        assertEquals("Reunião até 15h", bobsViewOfAlice?.receivedStatusMessage)
        // Bob's own local label for the contact is untouched — self-reported profile data never
        // overwrites the name Bob himself chose.
        assertEquals("Alice", bobsViewOfAlice?.displayName)
    }

    @Test
    fun `a profile update carries bio and interesses through to the receiving contact record`() = runTest {
        val remote = FakeChatMessageRemoteClient()
        val pair = establishedPair(remote)
        val (alice, bob, _, _, _, bobContacts) = pair

        alice.sendProfileUpdate(
            "conv-1", displayName = "Alice", statusMessage = "Disponível",
            bio = "Gosto de trilhas.", interesses = listOf("trilhas", "café"),
        )
        bob.fetchAndDecryptNew()

        val bobsViewOfAlice = bobContacts.load("conv-1")
        assertEquals("Gosto de trilhas.", bobsViewOfAlice?.receivedBio)
        assertEquals(listOf("trilhas", "café"), bobsViewOfAlice?.receivedInteresses)
    }

    @Test
    fun `a profile update never triggers an E2EE delivery receipt`() = runTest {
        val remote = FakeChatMessageRemoteClient()
        val (alice, bob) = establishedPair(remote)

        alice.sendProfileUpdate("conv-1", displayName = "Alice", statusMessage = "")
        bob.fetchAndDecryptNew()

        // If a receipt had been wrongly sent back, it would be sitting in alice's queue now.
        assertTrue(alice.fetchAndDecryptNew().isEmpty())
    }

    @Test
    fun `a profile update for a conversation with no local contact record is a harmless no-op`() = runTest {
        val remote = FakeChatMessageRemoteClient()
        val pair = establishedPair(remote)
        val (alice, bob, _, _, _, bobContacts) = pair
        bobContacts.delete("conv-1") // e.g. bob removed the connection locally

        alice.sendProfileUpdate("conv-1", displayName = "Alice", statusMessage = "oi")
        bob.fetchAndDecryptNew()

        assertNull(bobContacts.load("conv-1"))
    }

    @Test
    fun `a text message without a reply reference decodes with a null one`() = runTest {
        val remote = FakeChatMessageRemoteClient()
        val (alice, bob) = establishedPair(remote)

        alice.sendText("conv-1", "oi")
        assertNull(bob.fetchAndDecryptNew().single().replyToMessageId)
    }

    @Test
    fun `a reply to the other side's message is flipped to the receiver's own addressing`() = runTest {
        val remote = FakeChatMessageRemoteClient()
        val (alice, bob) = establishedPair(remote)

        alice.sendText("conv-1", "oi bob!") // alice's own id for this: SENT-0
        bob.fetchAndDecryptNew() // bob's own id for the SAME message: RECEIVED-0

        // Bob replies to what HE calls "RECEIVED-0" — alice's message, from his point of view.
        bob.sendText("conv-1", "oi alice!", replyToMessageId = "RECEIVED-0")
        val aliceIncoming = alice.fetchAndDecryptNew()

        assertEquals(1, aliceIncoming.size)
        // On alice's side the exact same original message is her own "SENT-0" — the flip must
        // land on that, not on bob's "RECEIVED-0" string taken literally.
        assertEquals("SENT-0", aliceIncoming.single().replyToMessageId)
    }

    @Test
    fun `a reply to one's own earlier message is also flipped correctly for the other side`() = runTest {
        val remote = FakeChatMessageRemoteClient()
        val (alice, bob) = establishedPair(remote)

        alice.sendText("conv-1", "primeira") // alice's own id: SENT-0
        alice.sendText("conv-1", "segunda, respondendo a mim mesma", replyToMessageId = "SENT-0")

        val reply = bob.fetchAndDecryptNew().single { it.text.startsWith("segunda") }
        // On bob's side, alice's "SENT-0" is his own "RECEIVED-0".
        assertEquals("RECEIVED-0", reply.replyToMessageId)
    }

    @Test
    fun `a malformed reply reference is dropped as null instead of failing the whole message`() = runTest {
        val remote = FakeChatMessageRemoteClient()
        val (alice, bob) = establishedPair(remote)

        alice.send("conv-1", ChatMessagePayload.Text("oi", replyToMessageId = "nonsense"))
        val incoming = bob.fetchAndDecryptNew()

        assertEquals(1, incoming.size)
        assertEquals("oi", incoming.single().text)
        assertNull(incoming.single().replyToMessageId)
    }

    @Test
    fun `send refuses to send anything to a blocked contact`() = runTest {
        val remote = FakeChatMessageRemoteClient()
        val pair = establishedPair(remote)
        val (alice, _, aliceStore, _, aliceContacts) = pair
        aliceContacts.setBlocked("conv-1", true)

        val result = runCatching { alice.sendText("conv-1", "oi bob") }

        assertTrue(result.isFailure)
        // Nothing was encrypted or persisted — a blocked send doesn't even touch the local outbox.
        assertTrue(aliceStore.pendingOutbox("conv-1").isEmpty())
    }

    @Test
    fun `fetchAndDecryptNew leaves a blocked contact's envelopes untouched until unblocked`() = runTest {
        val remote = FakeChatMessageRemoteClient()
        val pair = establishedPair(remote)
        val (alice, bob, _, _, _, bobContacts) = pair
        bobContacts.setBlocked("conv-1", true)

        alice.sendText("conv-1", "oi bob")
        assertTrue(bob.fetchAndDecryptNew().isEmpty())

        bobContacts.setBlocked("conv-1", false)
        val afterUnblock = bob.fetchAndDecryptNew()
        assertEquals(1, afterUnblock.size)
        assertEquals("oi bob", afterUnblock.single().text)
    }

    @Test
    fun `a blocked contact's profile update is neither sent nor ever applied`() = runTest {
        val remote = FakeChatMessageRemoteClient()
        val pair = establishedPair(remote)
        val (alice, bob, _, _, aliceContacts, bobContacts) = pair
        aliceContacts.setBlocked("conv-1", true)

        val result = runCatching { alice.sendProfileUpdate("conv-1", "Alice", "oi") }

        assertTrue(result.isFailure)
        assertTrue(bob.fetchAndDecryptNew().isEmpty())
        assertNull(bobContacts.load("conv-1")?.receivedDisplayName)
    }

    @Test
    fun `an avatar update is saved for the contact and never surfaces as a message`() = runTest {
        val remote = FakeChatMessageRemoteClient()
        val pair = establishedPair(remote)
        val (alice, bob, _, _, _, _, _, bobAvatars) = pair
        val avatarBytes = byteArrayOf(1, 2, 3, 4, 5)

        alice.sendAvatarUpdate("conv-1", avatarBytes)
        val incoming = bob.fetchAndDecryptNew()

        assertTrue(incoming.isEmpty())
        assertTrue(avatarBytes.contentEquals(bobAvatars.loadForContact("conv-1")))
    }

    @Test
    fun `an avatar update with null bytes removes a previously received avatar`() = runTest {
        val remote = FakeChatMessageRemoteClient()
        val pair = establishedPair(remote)
        val (alice, bob, _, _, _, _, _, bobAvatars) = pair

        alice.sendAvatarUpdate("conv-1", byteArrayOf(9, 9, 9))
        bob.fetchAndDecryptNew()
        assertTrue(bobAvatars.loadForContact("conv-1") != null)

        alice.sendAvatarUpdate("conv-1", null)
        bob.fetchAndDecryptNew()
        assertNull(bobAvatars.loadForContact("conv-1"))
    }

    @Test
    fun `an avatar update never triggers an E2EE delivery receipt`() = runTest {
        val remote = FakeChatMessageRemoteClient()
        val (alice, bob) = establishedPair(remote)

        alice.sendAvatarUpdate("conv-1", byteArrayOf(1))
        bob.fetchAndDecryptNew()

        assertTrue(alice.fetchAndDecryptNew().isEmpty())
    }

    @Test
    fun `a blocked contact's avatar update is neither sent nor ever applied`() = runTest {
        val remote = FakeChatMessageRemoteClient()
        val pair = establishedPair(remote)
        val (alice, bob, _, _, aliceContacts, _, _, bobAvatars) = pair
        aliceContacts.setBlocked("conv-1", true)

        val result = runCatching { alice.sendAvatarUpdate("conv-1", byteArrayOf(1)) }

        assertTrue(result.isFailure)
        assertTrue(bob.fetchAndDecryptNew().isEmpty())
        assertNull(bobAvatars.loadForContact("conv-1"))
    }
}
