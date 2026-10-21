package com.unyx.atlas.feature.connect.profile

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

const val AVATAR_TARGET_SIZE_PX = 512
const val AVATAR_MAX_FILE_BYTES = 256 * 1024
private const val AVATAR_MAX_INPUT_BYTES = 10L * 1024 * 1024
private const val AVATAR_MAX_INPUT_MEGAPIXELS = 20.0
private const val EDITOR_PREVIEW_MAX_SIDE_PX = 1600

/** Square selection in normalized coordinates of the auto-oriented image. */
data class AvatarCrop(
    val leftFraction: Float,
    val topFraction: Float,
    val sizeFraction: Float,
)

sealed interface AvatarEditorLoadResult {
    data class Success(val bitmap: Bitmap) : AvatarEditorLoadResult
    data class Rejected(val reason: String) : AvatarEditorLoadResult
}

sealed interface AvatarProcessingResult {
    data class Success(val jpegBytes: ByteArray) : AvatarProcessingResult
    data class Rejected(val reason: String) : AvatarProcessingResult
}

private sealed interface AvatarInputResult {
    data class Valid(val bytes: ByteArray) : AvatarInputResult
    data class Invalid(val reason: String) : AvatarInputResult
}

/** Validates, auto-orients and crops profile photos. Re-encoding removes the source EXIF data. */
object AvatarImagePipeline {

    /** Loads a bounded preview for the interactive crop screen, with EXIF orientation applied. */
    fun prepareForEditing(resolver: ContentResolver, uri: Uri): AvatarEditorLoadResult {
        val inputBytes = when (val result = readAndValidate(resolver, uri)) {
            is AvatarInputResult.Valid -> result.bytes
            is AvatarInputResult.Invalid -> return AvatarEditorLoadResult.Rejected(result.reason)
        }
        val decoded = decodeOriented(inputBytes, EDITOR_PREVIEW_MAX_SIDE_PX)
            ?: return AvatarEditorLoadResult.Rejected("Não foi possível abrir a imagem.")
        return AvatarEditorLoadResult.Success(decoded)
    }

    /** Re-decodes the source at full useful resolution and applies the crop chosen in the editor. */
    fun process(
        resolver: ContentResolver,
        uri: Uri,
        crop: AvatarCrop,
        extraRotationDegrees: Int = 0,
    ): AvatarProcessingResult {
        val inputBytes = when (val result = readAndValidate(resolver, uri)) {
            is AvatarInputResult.Valid -> result.bytes
            is AvatarInputResult.Invalid -> return AvatarProcessingResult.Rejected(result.reason)
        }
        var oriented = decodeOriented(inputBytes, 2048)
            ?: return AvatarProcessingResult.Rejected("Não foi possível abrir a imagem.")

        if (extraRotationDegrees % 360 != 0) {
            val rotated = rotate(oriented, extraRotationDegrees)
            if (rotated !== oriented) oriented.recycle()
            oriented = rotated
        }

        val square = cropToSquare(oriented, crop)
        if (square !== oriented) oriented.recycle()
        val resized = if (square.width == AVATAR_TARGET_SIZE_PX) square else {
            Bitmap.createScaledBitmap(square, AVATAR_TARGET_SIZE_PX, AVATAR_TARGET_SIZE_PX, true).also {
                if (it !== square) square.recycle()
            }
        }

        val jpegBytes = compressUnderLimit(resized, AVATAR_MAX_FILE_BYTES)
        resized.recycle()
        return if (jpegBytes != null) {
            AvatarProcessingResult.Success(jpegBytes)
        } else {
            AvatarProcessingResult.Rejected("Não foi possível comprimir a imagem o suficiente.")
        }
    }

    private fun readAndValidate(resolver: ContentResolver, uri: Uri): AvatarInputResult {
        val bytes = runCatching { resolver.openInputStream(uri)?.use(::readBounded) }.getOrNull()
        if (bytes == null) {
            return AvatarInputResult.Invalid("Não foi possível ler a imagem selecionada.")
        }
        if (bytes.size > AVATAR_MAX_INPUT_BYTES) {
            return AvatarInputResult.Invalid("Imagem maior que 10 MB — escolha uma menor.")
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return AvatarInputResult.Invalid("Arquivo não reconhecido como imagem.")
        }
        val megapixels = (bounds.outWidth.toLong() * bounds.outHeight.toLong()) / 1_000_000.0
        if (megapixels > AVATAR_MAX_INPUT_MEGAPIXELS) {
            return AvatarInputResult.Invalid("Imagem com mais de 20 megapixels — escolha uma menor.")
        }
        return AvatarInputResult.Valid(bytes)
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (total <= AVATAR_MAX_INPUT_BYTES) {
            val remaining = (AVATAR_MAX_INPUT_BYTES + 1 - total).coerceAtMost(buffer.size.toLong()).toInt()
            val count = input.read(buffer, 0, remaining)
            if (count < 0) break
            output.write(buffer, 0, count)
            total += count
        }
        return output.toByteArray()
    }

    private fun decodeOriented(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sampleSize = 1
        val shortestSide = minOf(bounds.outWidth, bounds.outHeight)
        while (
            maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > maxSide &&
            shortestSide / (sampleSize * 2) >= AVATAR_TARGET_SIZE_PX
        ) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return null

        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val normalized = applyExifOrientation(decoded, orientation)
        if (normalized !== decoded) decoded.recycle()
        return normalized
    }

    /** Applies all eight EXIF transforms, including mirrored and transposed images. */
    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) return bitmap
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val matrix = Matrix()
        val values = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> floatArrayOf(-1f, 0f, width, 0f, 1f, 0f, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> floatArrayOf(-1f, 0f, width, 0f, -1f, height, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> floatArrayOf(1f, 0f, 0f, 0f, -1f, height, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_ROTATE_90 -> floatArrayOf(0f, -1f, height, 1f, 0f, 0f, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_TRANSVERSE -> floatArrayOf(0f, -1f, height, -1f, 0f, width, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_ROTATE_270 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, width, 0f, 0f, 1f)
            else -> return bitmap
        }
        matrix.setValues(values)
        return runCatching { Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true) }
            .getOrDefault(bitmap)
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { setRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun cropToSquare(bitmap: Bitmap, crop: AvatarCrop): Bitmap {
        val shortestSide = minOf(bitmap.width, bitmap.height)
        val side = (crop.sizeFraction.coerceIn(0.05f, 1f) * shortestSide).toInt().coerceIn(1, shortestSide)
        val left = (crop.leftFraction.coerceAtLeast(0f) * bitmap.width).toInt().coerceIn(0, bitmap.width - side)
        val top = (crop.topFraction.coerceAtLeast(0f) * bitmap.height).toInt().coerceIn(0, bitmap.height - side)
        return Bitmap.createBitmap(bitmap, left, top, side, side)
    }

    private fun compressUnderLimit(bitmap: Bitmap, maxBytes: Int): ByteArray? {
        var quality = 90
        while (quality >= 20) {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            val bytes = output.toByteArray()
            if (bytes.size <= maxBytes) return bytes
            quality -= 15
        }
        return null
    }
}
