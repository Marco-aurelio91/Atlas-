package com.unyx.atlas.feature.connect.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.nio.charset.StandardCharsets

/** Draws our own [PairingInvitation] card as a QR bitmap — ZXing's `core` module only (pure
 *  Java, Apache-2.0, no protocol/crypto of its own: see `gradle/libs.versions.toml`'s comment).
 *  The card's bytes are already UTF-8 JSON text (see `PairingInvitation.serialize()`), so this
 *  passes the content straight through as a `String` — no extra base64/binary-mode layer. */
object QrCodeGenerator {

    /** Error correction "H" (~30% of the symbol can be damaged/obscured and still decode) —
     *  deliberately the highest level ZXing offers: this is read off a phone screen photographing
     *  another phone screen, under whatever ambient lighting/glare/angle happens in the moment, not
     *  a printed, scanner-optimized label. */
    fun generate(cardBytes: ByteArray, sizePx: Int): Bitmap {
        val content = String(cardBytes, StandardCharsets.UTF_8)
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}
