package com.unyx.atlas.feature.notes

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

/**
 * Native Android date-then-time picker dialogs chained together (no extra Compose dependency for
 * something this app already reaches the platform API for elsewhere — see VoiceController). Returns
 * a launcher function; calling it shows the date picker, then the time picker, then invokes
 * [onPicked] with an ISO-8601 local datetime string (no offset — matches how [aiCreateReminder]'s
 * fallback parse also accepts a plain [LocalDateTime]).
 */
@Composable
fun rememberDateTimePicker(onPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    return {
        val now = Calendar.getInstance()
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        val iso = LocalDateTime.of(year, month + 1, dayOfMonth, hour, minute).toString()
                        onPicked(iso)
                    },
                    now.get(Calendar.HOUR_OF_DAY),
                    now.get(Calendar.MINUTE),
                    true,
                ).show()
            },
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH),
        ).show()
    }
}

private val REMINDER_DISPLAY_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM 'às' HH:mm", Locale("pt", "BR"))

/** [MemoryNote.lembreteEm]/[AiResult.Reminder.whenIso] are stored as plain ISO datetime strings —
 * either offset-less (from [rememberDateTimePicker]) or with an offset (from the ✨ "Criar
 * lembrete" action's own parse, see [NotesViewModel.aiCreateReminder]). Never shown raw to the user
 * (e.g. "2026-09-20T18:00") — falls back to the raw string only if neither parse succeeds, which
 * shouldn't happen given both writers already validate the format before storing it. */
fun formatReminder(iso: String): String =
    runCatching { OffsetDateTime.parse(iso).format(REMINDER_DISPLAY_FORMAT) }
        .recoverCatching { LocalDateTime.parse(iso).format(REMINDER_DISPLAY_FORMAT) }
        .getOrDefault(iso)
