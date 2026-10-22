package com.unyx.atlas.feature.connect.notifications

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Mirrors `feature:chat`'s own `AutomationScheduler`/`feature:updater`'s `UpdateScheduler`
 *  exactly — same `enqueueUniquePeriodicWork`/`KEEP` pattern, scheduled unconditionally at process
 *  start (not gated on "has the user paired with anyone yet"): an idle poll with no pending
 *  conversations costs nothing worth branching on, and this avoids having to (re)schedule or
 *  cancel the work on every pairing/removal. */
object ChatNotificationScheduler {
    private const val WORK = "atlas-chat-notification-poll"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ChatNotificationWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Stops the poll — used while Atlas Connect is hidden, so a build that previously scheduled it
     *  doesn't keep waking the app for a feature nobody can reach. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }
}
