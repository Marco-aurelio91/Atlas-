package com.unyx.atlas.chatpairing

import com.unyx.atlas.chatcrypto.ChatCryptoProvider
import com.unyx.atlas.chatcrypto.ChatIdentityKeyPair
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets

/** Mirrors [PairingInvitationTest]'s structure for the other half of the handshake — B's reply
 *  card. The one rejection case unique to [PairingAcceptance] (not applicable to
 *  [PairingInvitation], which has nothing to match against) is the `expectedInvitationId` check:
 *  an otherwise well-formed acceptance that answers a *different* invite must be rejected rather
 *  than silently accepted (see [PairingEndToEndTest] for that same case exercised end-to-end). */
class PairingAcceptanceTest {

    @Before
    fun setUp() {
        ChatCryptoProvider.install()
    }

    private fun freshInvitation(): com.unyx.atlas.chatpairing.PairingInvitation =
        PairingInvitation.create(
            identity = ChatIdentityKeyPair.generate(),
            atlasUserId = "user-alice",
            deviceId = "device-alice",
            deviceEpoch = 1,
            displayName = "Alice",
            nowEpochSeconds = 1_000_000L,
        )

    private fun freshAcceptance(invitation: PairingInvitation = freshInvitation()): PairingAcceptance =
        PairingAcceptance.accept(
            invitation = invitation,
            identity = ChatIdentityKeyPair.generate(),
            atlasUserId = "user-bob",
            deviceId = "device-bob",
            deviceEpoch = 1,
            displayName = "Bob",
        )

    @Test
    fun `serialize then parse round trips to an identical acceptance`() {
        val invitation = freshInvitation()
        val original = freshAcceptance(invitation)
        val parsed = PairingAcceptance.parse(original.serialize(), expectedInvitationId = invitation.invitationId)
        assertEquals(original, parsed)
    }

    @Test
    fun `the public key carried in the acceptance is exactly the accepting identity's own public key`() {
        val invitation = freshInvitation()
        val identity = ChatIdentityKeyPair.generate()
        val acceptance = PairingAcceptance.accept(
            invitation = invitation, identity = identity, atlasUserId = "user-bob",
            deviceId = "device-bob", deviceEpoch = 1, displayName = "Bob",
        )
        assertArrayEquals(identity.publicKeyBytes, acceptance.publicKeyBytes)
    }

    @Test
    fun `an acceptance answering a different invitation id is rejected`() {
        val invitation = freshInvitation()
        val acceptance = freshAcceptance(invitation)
        assertThrows(InvalidPairingCardException::class.java) {
            PairingAcceptance.parse(acceptance.serialize(), expectedInvitationId = "some-other-invitation-id")
        }
    }

    @Test
    fun `an acceptance altered to invalid JSON is rejected safely, not crashing the parser`() {
        val invitation = freshInvitation()
        val tampered = "isto nao e uma aceitacao valida { } [ ] ???".toByteArray(StandardCharsets.UTF_8)
        assertThrows(InvalidPairingCardException::class.java) {
            PairingAcceptance.parse(tampered, expectedInvitationId = invitation.invitationId)
        }
    }

    @Test
    fun `an acceptance altered to valid JSON missing a required field is rejected safely`() {
        val invitation = freshInvitation()
        val acceptance = freshAcceptance(invitation)
        val mutilated = JSONObject(String(acceptance.serialize(), StandardCharsets.UTF_8))
        mutilated.remove("public_key_b64")
        assertThrows(InvalidPairingCardException::class.java) {
            PairingAcceptance.parse(
                mutilated.toString().toByteArray(StandardCharsets.UTF_8),
                expectedInvitationId = invitation.invitationId,
            )
        }
    }

    @Test
    fun `an unsupported format version is rejected rather than guessed at`() {
        val invitation = freshInvitation()
        val acceptance = freshAcceptance(invitation)
        val wrongVersion = JSONObject(String(acceptance.serialize(), StandardCharsets.UTF_8))
        wrongVersion.put("format_version", 99)
        assertThrows(InvalidPairingCardException::class.java) {
            PairingAcceptance.parse(
                wrongVersion.toString().toByteArray(StandardCharsets.UTF_8),
                expectedInvitationId = invitation.invitationId,
            )
        }
    }

    @Test
    fun `a blank display name is rejected at creation time`() {
        val invitation = freshInvitation()
        assertThrows(IllegalArgumentException::class.java) {
            PairingAcceptance.accept(
                invitation = invitation,
                identity = ChatIdentityKeyPair.generate(),
                atlasUserId = "user-bob",
                deviceId = "device-bob",
                deviceEpoch = 1,
                displayName = "   ",
            )
        }
    }
}
