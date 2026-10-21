package com.unyx.atlas.feature.connect.nfc

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The invitation card bytes this device is currently offering over NFC, if any — Android
 * instantiates [NfcCardService] itself (it's a `Service`, not something `ConnectPairingViewModel`
 * can construct with the bytes already in hand), so this is the handoff point between the two.
 * A plain `@Volatile` var, not a `StateFlow` or anything observable: nothing in this app needs to
 * react to it changing, only read the latest value when an APDU arrives. [current] holds the
 * SAME bytes already drawn on-screen as a QR bitmap — no greater exposure than what a camera
 * pointed at the screen could already read.
 */
@Singleton
class NfcCardPayloadHolder @Inject constructor() {
    @Volatile
    var current: ByteArray? = null
}
