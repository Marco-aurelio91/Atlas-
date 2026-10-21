package com.unyx.atlas.feature.connect.nfc

/**
 * The APDU protocol that moves a pairing invitation's bytes (the same `invitation.serialize()`
 * JSON a QR code carries — see `PairingInvitation`'s own doc) over NFC, chunked to fit inside
 * ISO 7816-4 short APDUs. Pure byte manipulation, no `android.nfc.*` types — both the card side
 * ([com.unyx.atlas.feature.connect.nfc.NfcCardService]) and the reader side
 * ([com.unyx.atlas.feature.connect.nfc.NfcCardReader]) are thin Android glue around this, so the
 * protocol itself is testable on the plain JVM.
 *
 * Android Beam (direct NDEF push) was removed in Android 10 — HCE (Host Card Emulation) is the
 * only phone-to-phone NFC path left, and it has asymmetric roles: one phone emulates a "card"
 * (this protocol's card side), the other reads it (reader side). [AID] is a proprietary,
 * non-ISO-registered Application Identifier (the `F0` prefix is reserved for exactly this —
 * national/proprietary use without paying for an ISO 7816-5 registration), so there is nothing to
 * register anywhere, only to agree on between this app's two sides.
 */
object NfcCardTransfer {

    /** "ATLAS" in ASCII, `F0`-prefixed per ISO 7816-5's proprietary range. */
    val AID: ByteArray = byteArrayOf(0xF0.toByte(), 0x41, 0x54, 0x4C, 0x41, 0x53)

    /** Comfortably under the 256-byte short-APDU response limit every NFC reader supports,
     *  without needing to negotiate extended-length APDUs. `PairingInvitation.MAX_CARD_BYTES` is
     *  4096, so a card never needs more than ~21 chunks — well inside a single unsigned byte
     *  index. */
    const val CHUNK_SIZE = 200

    private const val CLA_PROPRIETARY: Byte = 0x80.toByte()
    private const val INS_GET_LENGTH: Byte = 0xC0.toByte()
    private const val INS_GET_CHUNK: Byte = 0xC1.toByte()
    private val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
    private val SW_WRONG_PARAMS = byteArrayOf(0x6A.toByte(), 0x86.toByte())
    private val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00)

    fun splitIntoChunks(bytes: ByteArray): List<ByteArray> =
        if (bytes.isEmpty()) emptyList() else bytes.toList().chunked(CHUNK_SIZE).map { it.toByteArray() }

    /**
     * Card side: builds the response APDU for one incoming command APDU. [payload] is the full
     * invitation card bytes currently on offer (null = nothing to present right now — e.g. no
     * invitation has been generated yet). Stateless on purpose: every `GET_CHUNK` is
     * self-contained (the index says which slice), so a dropped/retried tap after
     * `TagLostException` just re-asks for whatever chunk it still needs — nothing here needs to
     * remember where a previous session left off.
     */
    fun buildResponse(commandApdu: ByteArray, payload: ByteArray?): ByteArray {
        if (payload == null) return SW_INS_NOT_SUPPORTED
        if (isSelectApdu(commandApdu)) return SW_OK
        if (commandApdu.size < 4 || commandApdu[0] != CLA_PROPRIETARY) return SW_INS_NOT_SUPPORTED
        return when (commandApdu[1]) {
            INS_GET_LENGTH -> {
                val length = payload.size
                byteArrayOf((length shr 8).toByte(), (length and 0xFF).toByte()) + SW_OK
            }
            INS_GET_CHUNK -> {
                val index = commandApdu[2].toInt() and 0xFF
                val chunks = splitIntoChunks(payload)
                if (index >= chunks.size) SW_WRONG_PARAMS else chunks[index] + SW_OK
            }
            else -> SW_INS_NOT_SUPPORTED
        }
    }

    private fun isSelectApdu(apdu: ByteArray): Boolean =
        apdu.size >= 4 && apdu[0] == 0x00.toByte() && apdu[1] == 0xA4.toByte()

    fun buildSelectApdu(): ByteArray =
        byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, AID.size.toByte()) + AID + byteArrayOf(0x00)

    fun buildGetLengthApdu(): ByteArray = byteArrayOf(CLA_PROPRIETARY, INS_GET_LENGTH, 0x00, 0x00, 0x00)

    fun buildGetChunkApdu(index: Int): ByteArray = byteArrayOf(CLA_PROPRIETARY, INS_GET_CHUNK, index.toByte(), 0x00, 0x00)

    /**
     * Reader side: drives the whole SELECT → GET_LENGTH → GET_CHUNK* exchange and returns the
     * fully assembled card bytes. [transceive] is injected (the real implementation wraps
     * `IsoDep.transceive`) so this is testable without any NFC hardware — a fake can just call
     * straight into [buildResponse] on the other end. Throws [IllegalStateException] on any
     * non-`90 00` status — the caller (`NfcCardReader`) decides whether that means "show an
     * error" or "the tag was lost, let the user tap again".
     */
    fun readCard(transceive: (ByteArray) -> ByteArray): ByteArray {
        check(endsWithOk(transceive(buildSelectApdu()))) { "SELECT da AID falhou" }
        val lengthResponse = transceive(buildGetLengthApdu())
        check(endsWithOk(lengthResponse) && lengthResponse.size == 4) { "GET_LENGTH falhou" }
        val length = ((lengthResponse[0].toInt() and 0xFF) shl 8) or (lengthResponse[1].toInt() and 0xFF)
        val chunkCount = if (length == 0) 0 else (length + CHUNK_SIZE - 1) / CHUNK_SIZE
        val buffer = ByteArray(length)
        var offset = 0
        for (index in 0 until chunkCount) {
            val response = transceive(buildGetChunkApdu(index))
            check(endsWithOk(response)) { "GET_CHUNK($index) falhou" }
            val chunkBytes = response.copyOf(response.size - 2)
            chunkBytes.copyInto(buffer, offset)
            offset += chunkBytes.size
        }
        return buffer
    }

    private fun endsWithOk(apdu: ByteArray): Boolean =
        apdu.size >= 2 && apdu[apdu.size - 2] == 0x90.toByte() && apdu[apdu.size - 1] == 0x00.toByte()
}
