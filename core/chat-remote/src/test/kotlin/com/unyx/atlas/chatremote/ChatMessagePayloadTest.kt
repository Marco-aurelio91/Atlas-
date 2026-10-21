package com.unyx.atlas.chatremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatMessagePayloadTest {

    @Test
    fun `a text payload round-trips through encode and decode`() {
        val original = ChatMessagePayload.Text("oi, tudo bem?")
        val decoded = ChatMessagePayload.decode(ChatMessagePayload.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `an empty text payload round-trips too`() {
        val original = ChatMessagePayload.Text("")
        assertEquals(original, ChatMessagePayload.decode(ChatMessagePayload.encode(original)))
    }

    @Test
    fun `a delivery receipt payload round-trips through encode and decode`() {
        val original = ChatMessagePayload.DeliveryReceipt(forSequence = 4242L)
        val decoded = ChatMessagePayload.decode(ChatMessagePayload.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `a text payload with a reply reference round-trips through encode and decode`() {
        val original = ChatMessagePayload.Text(text = "sim, chegou!", replyToMessageId = "SENT-3")
        assertEquals(original, ChatMessagePayload.decode(ChatMessagePayload.encode(original)))
    }

    @Test
    fun `a text payload with no reply reference decodes with a null one, not an empty string`() {
        val decoded = ChatMessagePayload.decode(ChatMessagePayload.encode(ChatMessagePayload.Text("oi"))) as ChatMessagePayload.Text
        assertNull(decoded.replyToMessageId)
    }

    @Test
    fun `text and receipt encodings are never confused with each other`() {
        val textBytes = ChatMessagePayload.encode(ChatMessagePayload.Text("x"))
        val receiptBytes = ChatMessagePayload.encode(ChatMessagePayload.DeliveryReceipt(1L))

        assertEquals(ChatMessagePayload.Text("x"), ChatMessagePayload.decode(textBytes))
        assertEquals(ChatMessagePayload.DeliveryReceipt(1L), ChatMessagePayload.decode(receiptBytes))
    }

    @Test
    fun `a profile update payload round-trips through encode and decode`() {
        val original = ChatMessagePayload.ProfileUpdate(displayName = "Marco Aurélio", statusMessage = "Disponível")
        assertEquals(original, ChatMessagePayload.decode(ChatMessagePayload.encode(original)))
    }

    @Test
    fun `a profile update with an empty status message round-trips too`() {
        val original = ChatMessagePayload.ProfileUpdate(displayName = "Alice", statusMessage = "")
        assertEquals(original, ChatMessagePayload.decode(ChatMessagePayload.encode(original)))
    }

    @Test
    fun `a profile update with an empty display name round-trips too`() {
        val original = ChatMessagePayload.ProfileUpdate(displayName = "", statusMessage = "oi")
        assertEquals(original, ChatMessagePayload.decode(ChatMessagePayload.encode(original)))
    }

    @Test
    fun `a profile update with bio and interesses round-trips through encode and decode`() {
        val original = ChatMessagePayload.ProfileUpdate(
            displayName = "Marco Aurélio",
            statusMessage = "Disponível",
            bio = "Gosto de café, trilhas e tecnologia.",
            interesses = listOf("café", "trilhas", "fotografia"),
        )
        assertEquals(original, ChatMessagePayload.decode(ChatMessagePayload.encode(original)))
    }

    @Test
    fun `a profile update with no interesses decodes with an empty list, not a one-element list`() {
        val original = ChatMessagePayload.ProfileUpdate(displayName = "Alice", statusMessage = "", bio = "")
        val decoded = ChatMessagePayload.decode(ChatMessagePayload.encode(original)) as ChatMessagePayload.ProfileUpdate
        assertEquals(emptyList<String>(), decoded.interesses)
    }

    @Test
    fun `an interesse containing a comma survives round-trip intact`() {
        // The comma is exactly why interesses are joined with U+001F internally, not a plain comma —
        // this is the case that would break with the naive delimiter.
        val original = ChatMessagePayload.ProfileUpdate(
            displayName = "Alice", statusMessage = "", bio = "", interesses = listOf("rock, jazz e blues"),
        )
        val decoded = ChatMessagePayload.decode(ChatMessagePayload.encode(original)) as ChatMessagePayload.ProfileUpdate
        assertEquals(listOf("rock, jazz e blues"), decoded.interesses)
    }

    @Test
    fun `an avatar update payload round-trips through encode and decode`() {
        val original = ChatMessagePayload.AvatarUpdate(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
        assertEquals(original, ChatMessagePayload.decode(ChatMessagePayload.encode(original)))
    }

    @Test
    fun `an avatar update with null bytes (removal) round-trips too`() {
        val original = ChatMessagePayload.AvatarUpdate(null)
        val decoded = ChatMessagePayload.decode(ChatMessagePayload.encode(original)) as ChatMessagePayload.AvatarUpdate
        assertNull(decoded.avatarBytes)
    }

    @Test
    fun `avatar update and text encodings are never confused with each other`() {
        val avatarBytes = ChatMessagePayload.encode(ChatMessagePayload.AvatarUpdate(byteArrayOf(5, 5)))
        val textBytes = ChatMessagePayload.encode(ChatMessagePayload.Text("x"))

        assertEquals(ChatMessagePayload.AvatarUpdate(byteArrayOf(5, 5)), ChatMessagePayload.decode(avatarBytes))
        assertEquals(ChatMessagePayload.Text("x"), ChatMessagePayload.decode(textBytes))
    }

    @Test
    fun `empty bytes decode to null instead of throwing`() {
        assertNull(ChatMessagePayload.decode(ByteArray(0)))
    }

    @Test
    fun `an unknown type byte decodes to null instead of throwing`() {
        assertNull(ChatMessagePayload.decode(byteArrayOf(99)))
    }

    @Test
    fun `a truncated receipt payload decodes to null instead of throwing`() {
        // Type byte for DeliveryReceipt, but fewer than the 8 bytes a Long needs.
        assertNull(ChatMessagePayload.decode(byteArrayOf(1, 0, 0, 0)))
    }

    @Test
    fun `a text payload claiming a reply id longer than the remaining bytes decodes to null`() {
        // Type byte for Text, then a length prefix claiming 100 bytes of reply id with none present.
        assertNull(ChatMessagePayload.decode(byteArrayOf(0, 0, 100)))
    }
}
