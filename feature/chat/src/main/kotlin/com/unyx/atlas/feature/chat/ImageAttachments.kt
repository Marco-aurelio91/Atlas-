package com.unyx.atlas.feature.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream

/** Decodes, downscales (max 768px on the long edge — plenty for Claude's vision, and keeps the
 * base64 payload this app persists locally from ballooning) and JPEG-compresses a picked or
 * shared-in image. Caller runs this off the main thread. `internal` (not private to one file) —
 * both the full chat (attach-a-photo) and the lightweight Assistant screen (receiving an
 * ACTION_SEND image share) attach a photo the exact same way. Returns null on a corrupt/unreadable
 * URI rather than throwing, matching both callers' "silently skip the attachment" failure mode. */
internal fun decodeAndCompressImage(context: Context, uri: Uri): String? {
    val original = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
    val maxDimension = 768
    val scale = minOf(1f, maxDimension.toFloat() / maxOf(original.width, original.height))
    val scaled = if (scale < 1f) {
        Bitmap.createScaledBitmap(original, (original.width * scale).toInt(), (original.height * scale).toInt(), true)
    } else {
        original
    }
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

private val bitmapCache = android.util.LruCache<String, ImageBitmap>(30)

/** Decodes once per distinct [base64] string and caches it in memory — an attachment never
 * changes after it's picked/received, so there's no need to re-decode on every redraw or scroll. */
@Composable
internal fun rememberDecodedBitmap(base64: String?): ImageBitmap? = remember(base64) {
    if (base64 == null) null
    else {
        bitmapCache.get(base64) ?: runCatching {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.also {
                bitmapCache.put(base64, it)
            }
        }.getOrNull()
    }
}
