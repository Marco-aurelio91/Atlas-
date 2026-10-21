package com.unyx.atlas.feature.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** A small, fixed set of note color tokens — stored on [com.unyx.atlas.memory.MemoryNote.cor] as the
 * key (e.g. "amarelo"), not a raw hex value, so the palette can be retuned per-theme without a data
 * migration. Kept local to feature:notes (not core:designsystem) until a second feature needs it —
 * same reasoning as feature:chat's AtlasCore.kt staying local for now. */
private data class NoteColorToken(val key: String, val label: String, val light: Color, val dark: Color)

private val NOTE_COLOR_TOKENS = listOf(
    NoteColorToken("amarelo", "Amarelo", Color(0xFFF6E58A), Color(0xFF4A431F)),
    NoteColorToken("verde", "Verde", Color(0xFFB7E4C7), Color(0xFF294436)),
    NoteColorToken("azul", "Azul", Color(0xFFB8D4F0), Color(0xFF223A52)),
    NoteColorToken("rosa", "Rosa", Color(0xFFF3C6D8), Color(0xFF4A2A38)),
    NoteColorToken("roxo", "Roxo", Color(0xFFD6C6F0), Color(0xFF362A4A)),
    NoteColorToken("laranja", "Laranja", Color(0xFFF3D0AE), Color(0xFF4A331F)),
)

data class NoteColorOption(val key: String?, val label: String)

fun noteColorOptions(): List<NoteColorOption> =
    listOf(NoteColorOption(null, "Padrão")) + NOTE_COLOR_TOKENS.map { NoteColorOption(it.key, it.label) }

/** Card/editor background for [key] — null or an unrecognized key falls back to the theme's own
 * surface, i.e. "no color" (used both for the grid card background and the color-picker swatches). */
@Composable
fun noteColorSwatch(key: String?): Color {
    val token = NOTE_COLOR_TOKENS.firstOrNull { it.key == key } ?: return MaterialTheme.colorScheme.surfaceContainerHigh
    return if (androidx.compose.foundation.isSystemInDarkTheme()) token.dark else token.light
}
