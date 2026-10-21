package com.unyx.atlas.feature.connect.qr

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/** Feeds camera frames to ML Kit's on-device barcode scanner (see `gradle/libs.versions.toml`'s
 *  comment on why ML Kit here, not a third-party protocol library) and calls [onDecoded] once
 *  with the first QR_CODE it finds. [onDecoded] is always posted to the main thread — Play
 *  Services' own Task listener threading isn't something to rely on here (the analyzer itself
 *  runs on a background executor; without the explicit post, [onDecoded] could land on either),
 *  and the caller touches Compose `mutableStateOf` state, which must only happen on the main
 *  thread. Stops analyzing after the first hit ([done]) — a long-lived analyzer would keep
 *  calling back for the same QR held in frame, and the caller only wants exactly one scan per
 *  screen visit. */
class QrCodeAnalyzer(private val onDecoded: (ByteArray) -> Unit) : ImageAnalysis.Analyzer {
    private val done = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (done.get()) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val barcode = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                if (barcode != null && done.compareAndSet(false, true)) {
                    // rawBytes is what the QR's encoder actually wrote (no String round-trip);
                    // rawValue is ML Kit's best-effort text decoding of the same bytes — used only
                    // if a given device/ML Kit version doesn't populate rawBytes for some reason.
                    val bytes = barcode.rawBytes ?: barcode.rawValue?.toByteArray(StandardCharsets.UTF_8)
                    if (bytes != null) mainHandler.post { onDecoded(bytes) }
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }
}
