package com.unyx.atlas.chatpairing

import com.unyx.atlas.chatcrypto.ChatCryptoProvider
import com.unyx.atlas.chatcrypto.ChatIdentityKeyPair
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets

class PairingInvitationTest {

    @Before
    fun setUp() {
        ChatCryptoProvider.install()
    }

    private fun freshInvitation(nowEpochSeconds: Long = 1_000_000L): PairingInvitation =
        PairingInvitation.create(
            identity = ChatIdentityKeyPair.generate(),
            atlasUserId = "user-alice",
            deviceId = "device-alice",
            deviceEpoch = 1,
            displayName = "Alice",
            nowEpochSeconds = nowEpochSeconds,
        )

    @Test
    fun `serialize then parse round trips to an identical invitation`() {
        val original = freshInvitation()
        val parsed = PairingInvitation.parse(original.serialize())
        assertEquals(original, parsed)
    }

    @Test
    fun `fits comfortably within the plan's 4 KiB card limit`() {
        val invitation = freshInvitation()
        assertTrue(invitation.serialize().size <= PairingInvitation.MAX_CARD_BYTES)
    }

    @Test
    fun `T-05 an expired invitation is rejected safely`() {
        val invitation = PairingInvitation.create(
            identity = ChatIdentityKeyPair.generate(),
            atlasUserId = "user-alice",
            deviceId = "device-alice",
            deviceEpoch = 1,
            displayName = "Alice",
            nowEpochSeconds = 1_000_000L,
            validitySeconds = 60,
        )
        val bytes = invitation.serialize()
        // Still parses as a well-formed card when expiry isn't checked...
        PairingInvitation.parse(bytes)
        // ...but is rejected once the caller supplies "now" past expiry.
        assertThrows(InvalidPairingCardException::class.java) {
            PairingInvitation.parse(bytes, nowEpochSeconds = 1_000_000L + 61)
        }
    }

    @Test
    fun `T-05 a QR altered to invalid JSON is rejected safely, not crashing the parser`() {
        val tampered = "isto nao e um convite valido { } [ ] ???".toByteArray(StandardCharsets.UTF_8)
        assertThrows(InvalidPairingCardException::class.java) { PairingInvitation.parse(tampered) }
    }

    @Test
    fun `T-05 a QR altered to valid JSON missing a required field is rejected safely`() {
        val invitation = freshInvitation()
        val mutilated = JSONObject(String(invitation.serialize(), StandardCharsets.UTF_8))
        mutilated.remove("public_key_b64")
        assertThrows(InvalidPairingCardException::class.java) {
            PairingInvitation.parse(mutilated.toString().toByteArray(StandardCharsets.UTF_8))
        }
    }

    @Test
    fun `an oversized payload is rejected before attempting to parse it as JSON`() {
        val oversized = ByteArray(PairingInvitation.MAX_CARD_BYTES + 1)
        assertThrows(InvalidPairingCardException::class.java) { PairingInvitation.parse(oversized) }
    }

    @Test
    fun `an unsupported format version is rejected rather than guessed at`() {
        val invitation = freshInvitation()
        val wrongVersion = JSONObject(String(invitation.serialize(), StandardCharsets.UTF_8))
        wrongVersion.put("format_version", 99)
        assertThrows(InvalidPairingCardException::class.java) {
            PairingInvitation.parse(wrongVersion.toString().toByteArray(StandardCharsets.UTF_8))
        }
    }

    @Test
    fun `a blank display name is rejected at creation time`() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingInvitation.create(
                identity = ChatIdentityKeyPair.generate(),
                atlasUserId = "user-alice",
                deviceId = "device-alice",
                deviceEpoch = 1,
                displayName = "   ",
                nowEpochSeconds = 1_000_000L,
            )
        }
    }

    @Test
    fun `the public key carried in the card is exactly the identity's own public key`() {
        val identity = ChatIdentityKeyPair.generate()
        val invitation = PairingInvitation.create(
            identity = identity, atlasUserId = "user-alice", deviceId = "device-alice",
            deviceEpoch = 1, displayName = "Alice", nowEpochSeconds = 1_000_000L,
        )
        assertArrayEquals(identity.publicKeyBytes, invitation.publicKeyBytes)
    }
}
