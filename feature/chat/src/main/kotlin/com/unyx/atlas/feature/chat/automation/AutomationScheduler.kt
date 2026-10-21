package com.unyx.atlas.feature.chat.automation

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Mirrors feature:updater's UpdateScheduler exactly — same enqueueUniquePeriodicWork/KEEP pattern. */
object AutomationScheduler {
    private const val WORK = "atlas-automation-poll"

    /** 15 minutes is WorkManager's floor for periodic work — see [AutomationWorker]'s doc on why a
     * DailyTime automation can lag its target time rather than firing exactly on
     * it. Scheduled unconditionally at process start (not gated on "does the user have any
     * automations yet") — an idle poll over an empty list costs nothing worth branching on, and
     * this avoids having to (re)schedule/cancel on every automation add/remove. */
    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<AutomationWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
