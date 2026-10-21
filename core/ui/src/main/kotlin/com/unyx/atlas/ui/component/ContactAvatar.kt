package com.unyx.atlas.ui.component

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.unyx.atlas.ui.expressive.Accent

/** A round contact avatar: the photo when available, otherwise an initial on an accent circle.
 *  [photoBytes] (already-decrypted avatar bytes — plan §4, Atlas Connect's own avatars, which are
 *  never a plain URI since they're stored via `ChatAvatarStore`'s `EncryptedFile`) takes priority
 *  over [photoUri] when both are given; decoded directly to a [androidx.compose.ui.graphics.ImageBitmap]
 *  rather than routed through Coil, since the bytes are already in memory and don't need async
 *  loading/caching machinery built for remote/content-resolver sources. */
@Composable
fun ContactAvatar(name: String, photoUri: String?, modifier: Modifier = Modifier, size: Dp = 40.dp, photoBytes: ByteArray? = null) {
    if (photoBytes != null) {
        val imageBitmap = remember(photoBytes) {
            BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size)?.asImageBitmap()
        }
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier.size(size).clip(CircleShape),
            )
            return
        }
        // Falls through to the initials circle below if decoding failed — never crash on a
        // corrupted/truncated avatar someone else sent.
    }
    if (!photoUri.isNullOrEmpty()) {
        AsyncImage(
            model = photoUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape),
        )
    } else {
        Box(
            modifier = modifier.size(size).clip(CircleShape).background(Accent.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.firstOrNull()?.uppercase() ?: "?",
                color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Medium,
            )
        }
    }
}
