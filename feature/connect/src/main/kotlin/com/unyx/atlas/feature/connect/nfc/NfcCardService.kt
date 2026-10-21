package com.unyx.atlas.feature.connect.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The HCE "card" side of NFC pairing — see [NfcCardTransfer]'s own doc for why this, and not
 * Android Beam, is the only phone-to-phone NFC route left. Declared in `app`'s manifest (same
 * pattern as `AtlasOverlayService`/`AtlasScreenCaptureService`: the Service class lives in a
 * feature module, the `<service>` tag lives in `app` since that's where the final manifest is
 * assembled) and routed to by the OS whenever [NfcCardTransfer.AID] is selected while
 * [com.unyx.atlas.feature.connect.pairing.AddConnectionScreen]'s presenting screen has registered
 * it as the preferred service via `CardEmulation.setPreferredService`.
 *
 * Stateless by design (see [NfcCardTransfer.buildResponse]'s own doc) — Android can create and
 * destroy instances of this service across taps and that is never a correctness concern here.
 */
@AndroidEntryPoint
class NfcCardService : HostApduService() {

    @Inject lateinit var payloadHolder: NfcCardPayloadHolder

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray? {
        val apdu = commandApdu ?: return null
        return NfcCardTransfer.buildResponse(apdu, payloadHolder.current)
    }

    override fun onDeactivated(reason: Int) {
        // No per-session state to clear — every command is self-contained (see
        // NfcCardTransfer's own doc). Deliberately empty.
    }
}
