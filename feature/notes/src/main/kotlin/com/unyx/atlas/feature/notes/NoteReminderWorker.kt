package com.unyx.atlas.feature.notes

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.unyx.atlas.memory.MemoryNote
import com.unyx.atlas.memory.MemoryRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import androidx.work.Operation

class NoteReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun schedule(note: MemoryNote): Boolean {
        val noteId = requireNotNull(note.id) { "A note must be saved before scheduling its reminder" }
        val reminderAt = requireNotNull(note.lembreteEm) { "A reminder time is required" }
        val dueAt = parseReminderInstant(reminderAt)
        val delayMillis = Duration.between(Instant.now(), dueAt).toMillis()
        val workName = workName(noteId)
        val workManager = WorkManager.getInstance(context)
        if (delayMillis <= 0) {
            workManager.cancelUniqueWork(workName).await()
            return true
        }

        val request = OneTimeWorkRequestBuilder<NoteReminderWorker>()
            .setInputData(workDataOf(NoteReminderWorker.NOTE_ID_KEY to noteId))
            .setInitialDelay(delayMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(workName, androidx.work.ExistingWorkPolicy.REPLACE, request).await()
        return notificationsAllowed()
    }

    suspend fun cancel(noteId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(noteId)).await()
    }

    private fun workName(noteId: String) = "note-reminder:$noteId"

    private fun notificationsAllowed(): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun parseReminderInstant(value: String): Instant =
        runCatching { OffsetDateTime.parse(value).toInstant() }
            .getOrElse { LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant() }

    private suspend fun Operation.await() {
        suspendCancellableCoroutine { continuation ->
            result.addListener({
                try {
                    result.get()
                    if (continuation.isActive) continuation.resume(Unit)
                } catch (failure: ExecutionException) {
                    if (continuation.isActive) continuation.resumeWithException(failure.cause ?: failure)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }, Executor { command -> command.run() })
        }
    }
}

@HiltWorker
class NoteReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val memoryRepository: MemoryRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val noteId = inputData.getString(NOTE_ID_KEY) ?: return Result.failure()
        return try {
            val note = memoryRepository.findNoteById(noteId) ?: return Result.success()
            val reminderAt = note.lembreteEm ?: return Result.success()
            val dueAt = parseReminderInstant(reminderAt)
            if (dueAt.isAfter(Instant.now())) return Result.retry()
            if (!notificationsAllowed()) return Result.success()
            showReminder(note)
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun notificationsAllowed(): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()

    private fun showReminder(note: MemoryNote) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Lembretes de notas", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val openNotes = Intent().apply {
            setClassName(applicationContext.packageName, "com.unyx.atlas.NotesActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            note.id.hashCode(),
            openNotes,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Lembrete do Atlas")
            .setContentText("Toque para abrir suas notas.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(note.id.hashCode(), notification)
    }

    private fun parseReminderInstant(value: String): Instant =
        runCatching { OffsetDateTime.parse(value).toInstant() }
            .getOrElse { LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant() }

    companion object {
        internal const val NOTE_ID_KEY = "note_id"
        private const val CHANNEL_ID = "note_reminders"
    }
}
