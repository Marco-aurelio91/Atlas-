package com.unyx.atlas.chatpairing

import com.unyx.atlas.chatcrypto.ChatCryptoProvider
import com.unyx.atlas.chatcrypto.ChatIdentityKeyPair
import com.unyx.atlas.chatcrypto.ChatSession
import com.unyx.atlas.chatcrypto.DecryptResult
import com.unyx.atlas.chatstorage.ChatConversationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * The full pipeline from `docs/ATLAS_CHAT_Plano_Completo_Criacao_e_Implantacao.md` §8.3, as far
 * as it can go without real NFC/QR hardware or a backend: A creates an invite, the card's *bytes*
 * cross to B exactly as NFC/QR would deliver them (nothing shared except the serialized
 * [ByteArray] — see each step's comment), B accepts, the acceptance's bytes cross back to A, both
 * sides independently compute the identical presencial fingerprint, both establish a
 * [ChatSession], and a message survives a simulated crash via [ChatConversationStore]. This is
 * the first test in the whole `core/chat-*` tree that exercises pairing, crypto, and storage
 * together instead of each module in isolation.
 */
class PairingEndToEndTest {

    @Before
    fun setUp() {
        ChatCryptoProvider.install()
    }

    private fun String.utf8() = toByteArray(StandardCharsets.UTF_8)
    private fun ByteArray.asText() = String(this, StandardCharsets.UTF_8)

    @Test
    fun `full pairing flow establishes matching sessions that can exchange real messages`() {
        val aliceIdentity = ChatIdentityKeyPair.generate()
        val bobIdentity = ChatIdentityKeyPair.generate()
        val now = 1_000_000L

        // --- A presents an invite (NFC tap or QR code) ---
        val invitation = PairingInvitation.create(
            identity = aliceIdentity, atlasUserId = "user-alice", deviceId = "device-alice",
            deviceEpoch = 1, displayName = "Alice", nowEpochSeconds = now,
        )
        val invitationBytes = invitation.serialize() // <- exactly what crosses over NFC/QR

        // --- B reads it and validates before doing anything else (plan §8.3 step 2) ---
        val receivedInvitation = PairingInvitation.parse(invitationBytes, nowEpochSeconds = now + 5)

        // --- B accepts; the acceptance's bytes are what need to reach A (today: a server claim
        //     this prototype doesn't have; here, just the bytes) ---
        val acceptance = PairingAcceptance.accept(
            invitation = receivedInvitation, identity = bobIdentity, atlasUserId = "user-bob",
            deviceId = "device-bob", deviceEpoch = 1, displayName = "Bob",
        )
        val acceptanceBytes = acceptance.serialize() // <- crosses back to A

        // --- A validates the acceptance answers the exact invite it sent (plan §8.3 step 4) ---
        val receivedAcceptance = PairingAcceptance.parse(acceptanceBytes, expectedInvitationId = invitation.invitationId)

        val conversationId = invitation.invitationId // one conversation per confirmed pairing, for this prototype

        // --- Both sides now hold the other's public key; compute the safety number independently
        //     (plan §8.3 step 5-6) — these must match for the presencial comparison to mean anything ---
        val fingerprintAtAlice = PairingFingerprint.compute(aliceIdentity.publicKeyBytes, receivedAcceptance.publicKeyBytes, conversationId)
        val fingerprintAtBob = PairingFingerprint.compute(bobIdentity.publicKeyBytes, receivedInvitation.publicKeyBytes, conversationId)
        assertEquals(fingerprintAtAlice, fingerprintAtBob)

        // --- Both confirm the presencial comparison matched, and fix the identities (plan §8.3
        //     step 7-8) by establishing the ratchet session ---
        val aliceSession = ChatSession.establish(
            local = aliceIdentity, localUserId = "user-alice", localDeviceId = "device-alice",
            remotePublicKeyBytes = receivedAcceptance.publicKeyBytes, remoteUserId = "user-bob",
            remoteDeviceId = "device-bob", conversationId = conversationId,
        )
        val bobSession = ChatSession.establish(
            local = bobIdentity, localUserId = "user-bob", localDeviceId = "device-bob",
            remotePublicKeyBytes = receivedInvitation.publicKeyBytes, remoteUserId = "user-alice",
            remoteDeviceId = "device-alice", conversationId = conversationId,
        )

        // --- The pairing is only useful if it actually produces a working encrypted channel ---
        val envelope = aliceSession.encrypt("ola bob, deu certo o pareamento!".utf8())
        val result = bobSession.decrypt(envelope)
        assertTrue(result is DecryptResult.Delivered)
        assertEquals("ola bob, deu certo o pareamento!", (result as DecryptResult.Delivered).plaintext.asText())

        // --- ...and that it survives a restart, tying in chat:storage too ---
        val store = ChatConversationStore(FakeSharedPreferences())
        store.persistAfterEncrypt(aliceSession.snapshot(), envelope)
        val restoredAliceSession = ChatSession.restore(store.loadSession(conversationId)!!)
        val secondEnvelope = restoredAliceSession.encrypt("segunda mensagem, apos reinicio".utf8())
        assertEquals(1L, secondEnvelope.sequence)
        val secondResult = bobSession.decrypt(secondEnvelope)
        assertTrue(secondResult is DecryptResult.Delivered)
    }

    @Test
    fun `an acceptance answering a different invitation is rejected`() {
        val aliceIdentity = ChatIdentityKeyPair.generate()
        val bobIdentity = ChatIdentityKeyPair.generate()
        val now = 1_000_000L

        val realInvitation = PairingInvitation.create(
            identity = aliceIdentity, atlasUserId = "user-alice", deviceId = "device-alice",
            deviceEpoch = 1, displayName = "Alice", nowEpochSeconds = now,
        )
        val unrelatedInvitation = PairingInvitation.create(
            identity = ChatIdentityKeyPair.generate(), atlasUserId = "user-carol", deviceId = "device-carol",
            deviceEpoch = 1, displayName = "Carol", nowEpochSeconds = now,
        )
        val acceptance = PairingAcceptance.accept(
            invitation = unrelatedInvitation, identity = bobIdentity, atlasUserId = "user-bob",
            deviceId = "device-bob", deviceEpoch = 1, displayName = "Bob",
        )

        assertThrows(InvalidPairingCardException::class.java) {
            PairingAcceptance.parse(acceptance.serialize(), expectedInvitationId = realInvitation.invitationId)
        }
    }
}
