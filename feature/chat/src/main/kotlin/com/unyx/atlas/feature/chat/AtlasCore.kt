package com.unyx.atlas.feature.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Activity states displayed by the chat indicator. */
enum class AtlasActivity { IDLE, LISTENING, THINKING, EXECUTING, SPEAKING, ERROR }

/**
 * Atlas's visual identity in place of a face or a generic spinner — a small "core" that sits still
 * at rest, pulses gently while [AtlasActivity.THINKING], and pulses faster in a distinct color
 * while [AtlasActivity.EXECUTING] — so a glance at the core alone (not just the status text) tells
 * "Atlas is deciding" from "Atlas is doing something real" apart.
 */
@Composable
fun AtlasCoreIndicator(
    activity: AtlasActivity,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
) {
    val scale = if (activity != AtlasActivity.IDLE) {
        val transition = rememberInfiniteTransition(label = "atlasCore")
        val animatedScale by transition.animateFloat(
            initialValue = 0.7f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                // Executing pulses noticeably faster than thinking — a small, wordless cue that
                // something is actively happening right now, not just being reasoned about.
                animation = tween(if (activity == AtlasActivity.EXECUTING) 320 else 600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "atlasCoreScale",
        )
        animatedScale
    } else {
        1f
    }
    val color = if (activity == AtlasActivity.EXECUTING) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(color),
    )
}
