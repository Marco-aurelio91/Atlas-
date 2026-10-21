package com.unyx.atlas.feature.chat

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Leave room for base64 expansion and the rest of the chat request under the gateway's body cap. */
internal const val MAX_PDF_BYTES = 10L * 1024 * 1024

/** Reads a picked PDF whole and base64-encodes it — no downscaling/compression possible for a
 * document the way [decodeAndCompressImage] does for a photo, so this instead enforces
 * [MAX_PDF_BYTES] up front. Shared by the full chat and the Assistant screen, same as the image
 * attachment helpers. Throws [PdfTooLargeException] for oversized files and [IOException] when
 * the selected document cannot be read. */
internal fun readAndEncodePdf(context: Context, uri: Uri): String {
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_PDF_BYTES) throw PdfTooLargeException()
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    } ?: throw IOException("Não foi possível abrir o PDF selecionado.")
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}

internal class PdfTooLargeException : IOException()

/** Best-effort display name for a picked document — falls back to a generic label rather than a
 * raw content:// URI, which means nothing to a user. */
internal fun displayNameFor(context: Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: "Documento PDF"
}
