package com.unyx.atlas.feature.connect.nfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NfcCardTransferTest {

    @Test
    fun `splitIntoChunks on empty bytes returns no chunks`() {
        assertEquals(emptyList<ByteArray>(), NfcCardTransfer.splitIntoChunks(ByteArray(0)))
    }

    @Test
    fun `splitIntoChunks on bytes smaller than one chunk returns a single chunk`() {
        val bytes = Random.nextBytes(50)
        val chunks = NfcCardTransfer.splitIntoChunks(bytes)
        assertEquals(1, chunks.size)
        assertArrayEquals(bytes, chunks[0])
    }

    @Test
    fun `splitIntoChunks on an exact multiple of the chunk size has no trailing empty chunk`() {
        val bytes = Random.nextBytes(NfcCardTransfer.CHUNK_SIZE * 3)
        val chunks = NfcCardTransfer.splitIntoChunks(bytes)
        assertEquals(3, chunks.size)
        chunks.forEach { assertEquals(NfcCardTransfer.CHUNK_SIZE, it.size) }
    }

    @Test
    fun `splitIntoChunks on the maximum invitation card size produces well under 256 chunks`() {
        val bytes = Random.nextBytes(4096) // PairingInvitation.MAX_CARD_BYTES
        val chunks = NfcCardTransfer.splitIntoChunks(bytes)
        assertTrue(chunks.size < 256) // must fit in one unsigned byte index (GET_CHUNK's P1)
        assertArrayEquals(bytes, chunks.reduce { acc, chunk -> acc + chunk })
    }

    @Test
    fun `a SELECT apdu always succeeds when a card is on offer`() {
        val response = NfcCardTransfer.buildResponse(NfcCardTransfer.buildSelectApdu(), payload = byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(0x90.toByte(), 0x00), response)
    }

    @Test
    fun `everything fails safely when no card is on offer yet`() {
        val response = NfcCardTransfer.buildResponse(NfcCardTransfer.buildGetLengthApdu(), payload = null)
        assertArrayEquals(byteArrayOf(0x6D.toByte(), 0x00), response)
    }

    @Test
    fun `GET_LENGTH reports the exact payload size`() {
        val payload = Random.nextBytes(321)
        val response = NfcCardTransfer.buildResponse(NfcCardTransfer.buildGetLengthApdu(), payload)
        assertEquals(4, response.size)
        val reportedLength = ((response[0].toInt() and 0xFF) shl 8) or (response[1].toInt() and 0xFF)
        assertEquals(321, reportedLength)
        assertArrayEquals(byteArrayOf(0x90.toByte(), 0x00), response.copyOfRange(2, 4))
    }

    @Test
    fun `GET_CHUNK with an out-of-range index fails with wrong-parameters, not a crash`() {
        val payload = Random.nextBytes(10) // fits in a single chunk — index 1 does not exist
        val response = NfcCardTransfer.buildResponse(NfcCardTransfer.buildGetChunkApdu(1), payload)
        assertArrayEquals(byteArrayOf(0x6A.toByte(), 0x86.toByte()), response)
    }

    @Test
    fun `an unrecognized instruction fails safely instead of throwing`() {
        val unknownApdu = byteArrayOf(0x80.toByte(), 0xFF.toByte(), 0x00, 0x00, 0x00)
        val response = NfcCardTransfer.buildResponse(unknownApdu, payload = byteArrayOf(1))
        assertArrayEquals(byteArrayOf(0x6D.toByte(), 0x00), response)
    }

    @Test
    fun `readCard reassembles a payload smaller than one chunk by driving the card's own responses`() {
        val payload = "hello atlas".toByteArray()
        val result = NfcCardTransfer.readCard { apdu -> NfcCardTransfer.buildResponse(apdu, payload) }
        assertArrayEquals(payload, result)
    }

    @Test
    fun `readCard reassembles a payload spanning several chunks in the right order`() {
        val payload = Random.nextBytes(NfcCardTransfer.CHUNK_SIZE * 5 + 37)
        val result = NfcCardTransfer.readCard { apdu -> NfcCardTransfer.buildResponse(apdu, payload) }
        assertArrayEquals(payload, result)
    }

    @Test
    fun `readCard reassembles a full-size 4096-byte invitation card`() {
        val payload = Random.nextBytes(4096)
        val result = NfcCardTransfer.readCard { apdu -> NfcCardTransfer.buildResponse(apdu, payload) }
        assertArrayEquals(payload, result)
    }

    @Test(expected = IllegalStateException::class)
    fun `readCard throws when the card has nothing to offer`() {
        NfcCardTransfer.readCard { apdu -> NfcCardTransfer.buildResponse(apdu, payload = null) }
    }
}
