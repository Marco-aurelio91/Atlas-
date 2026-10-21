package com.unyx.atlas.feature.chat

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
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.unyx.atlas.memory.MemoryRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Pushes the urgent half of the proactive nudges (overdue = CRITICAL, due today = ATTENTION) as a
 * system notification while the chat is closed — [ChatViewModel]'s own refresh loop is cancelled
 * in `onChatPaused`, so without this a deadline only surfaced the next time the user opened Atlas.
 *
 * Deliberately narrow, matching the in-app discipline ("a single relevant thing said clearly beats
 * several said quietly"):
 *  - only deadline nudges (same [deadlineNudges] the chat uses); routine suggestions stay in-app
 *    because they're only meaningful with the chat in front of the user;
 *  - ATTENTION honors learned rejection (dismissed-more-than-accepted); CRITICAL never does;
 *  - at most one notification per (kind, text) per day, and none during quiet hours.
 */
@HiltWorker
class ProactiveNudgeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val memoryRepository: MemoryRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!notificationsAllowed()) return Result.success()
        val now = LocalTime.now()
        if (now.hour < QUIET_UNTIL_HOUR || now.hour >= QUIET_FROM_HOUR) return Result.success()

        return try {
            val tasks = memoryRepository.openTasks()
            val projects = memoryRepository.projects()
            val today = LocalDate.now()
            val sent = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            deadlineNudges(tasks, projects, today)
                .filter { it.level == ProactiveLevel.CRITICAL || !isSuppressedByRejection(it.sourceKey) }
                .forEach { nudge ->
                    val fingerprint = "$today|${nudge.text}"
                    if (sent.getString(nudge.sourceKey, null) == fingerprint) return@forEach
                    show(nudge)
                    sent.edit().putString(nudge.sourceKey, fingerprint).apply()
                }
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Signed out / offline: nothing to say, and the next 30-minute pass tries again.
            Result.success()
        }
    }

    /** Same rule as [ChatViewModel]'s: ≥ 2 dismissals and more dismissals than acceptances. */
    private suspend fun isSuppressedByRejection(sourceKey: String): Boolean {
        val dismissed = runCatching { memoryRepository.eventsByType("nudge_dismissed:$sourceKey", limit = 20) }
            .getOrDefault(emptyList())
        if (dismissed.size < 2) return false
        val accepted = runCatching { memoryRepository.eventsByType("nudge_accepted:$sourceKey", limit = 20) }
            .getOrDefault(emptyList())
        return dismissed.size > accepted.size
    }

    private fun notificationsAllowed(): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()

    private fun show(nudge: ProactiveNudge) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Avisos do Atlas", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val openChat = Intent().apply {
            setClassName(applicationContext.packageName, "com.unyx.atlas.AtlasChatActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            nudge.sourceKey.hashCode(),
            openChat,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(if (nudge.level == ProactiveLevel.CRITICAL) "Atlas: atrasado" else "Atlas: para hoje")
            .setContentText(nudge.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(nudge.text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_TAG, nudge.sourceKey.hashCode(), notification)
    }

    companion object {
        private const val WORK = "atlas-proactive-nudges"
        private const val PREFS = "atlas_proactive_nudge_worker"
        private const val CHANNEL_ID = "atlas_proactive_nudges"
        private const val NOTIFICATION_TAG = "atlas-proactive"
        private const val QUIET_UNTIL_HOUR = 8
        private const val QUIET_FROM_HOUR = 22

        /** Same enqueueUniquePeriodicWork/KEEP pattern as AutomationScheduler; 30 minutes is plenty
         * for a day-granular deadline and keeps the battery cost negligible. Needs network for the
         * Supabase reads. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ProactiveNudgeWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
