package com.unyx.atlas.feature.connect.nfc

import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.NfcAdapter
import android.nfc.tech.IsoDep

/**
 * The reader side of NFC pairing — wraps [NfcAdapter.ReaderCallback] so
 * [com.unyx.atlas.feature.connect.pairing.AddConnectionScreen]'s scanning screen doesn't need to
 * touch `IsoDep` directly. [onCardRead] receives the fully assembled bytes (the exact same thing
 * [com.unyx.atlas.feature.connect.qr.QrCodeAnalyzer] hands back for a QR scan — both feed the
 * same `ConnectPairingViewModel.onCardBytesReceived`). [onTagLost] fires for a tap that didn't
 * stay in range long enough to finish — expected and recoverable (the user just taps again), not
 * the same kind of failure as a malformed/expired card, so it's a separate callback rather than
 * routing through the pairing error state.
 */
class NfcCardReader(
    private val onCardRead: (ByteArray) -> Unit,
    private val onTagLost: () -> Unit,
) : NfcAdapter.ReaderCallback {

    override fun onTagDiscovered(tag: Tag) {
        val isoDep = IsoDep.get(tag) ?: return // not an ISO-DEP tag — not what this protocol speaks
        try {
            isoDep.connect()
            isoDep.timeout = TIMEOUT_MILLIS
            val bytes = NfcCardTransfer.readCard { apdu -> isoDep.transceive(apdu) }
            onCardRead(bytes)
        } catch (lost: TagLostException) {
            onTagLost()
        } catch (failure: Exception) {
            // Anything else (IOException from transceive, IllegalStateException from readCard's
            // own checks on a malformed response) is treated the same as a lost tag — the far
            // side's own QR fallback already covers "this transport didn't work", so there's no
            // separate hard-failure UI needed here.
            onTagLost()
        } finally {
            runCatching { isoDep.close() }
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 3000
    }
}
