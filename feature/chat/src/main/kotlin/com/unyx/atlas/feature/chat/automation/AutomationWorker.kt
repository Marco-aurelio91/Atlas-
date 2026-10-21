package com.unyx.atlas.feature.chat.automation

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.ZoneId

/**
 * Polls every 15 minutes (WorkManager's floor for periodic work — there's no exact-alarm-per-
 * automation here, see [Automation.lastFiredDate]'s doc) and fires any enabled
 * [AutomationTrigger.DailyTime] automation whose target time has passed and hasn't already fired
 * today. Delayed WorkManager runs catch up once later that day. Event-driven triggers
 * (charger/headphones) don't go through this — see
 * [AutomationTriggerMonitor].
 */
@HiltWorker
class AutomationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val store: AutomationStore,
    private val executor: AutomationExecutor,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val now = LocalTime.now()
            val todayDate = LocalDate.now()
            val dayOfWeek = DayOfWeek.from(todayDate.atTime(now)).value
            val today = todayDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
            store.all()
                .filter { it.enabled && it.trigger is AutomationTrigger.DailyTime && it.lastFiredDate != today }
                .forEach { automation ->
                    val trigger = automation.trigger as AutomationTrigger.DailyTime
                    if (trigger.daysOfWeek.isNotEmpty() && dayOfWeek !in trigger.daysOfWeek) return@forEach
                    val target = LocalTime.of(trigger.hour, trigger.minute)
                    val minutesSinceTarget = java.time.Duration.between(target, now).toMinutes()
                    val scheduledAtMillis = todayDate.atTime(target).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    if (minutesSinceTarget >= 0 && automation.enabledSinceMillis <= scheduledAtMillis &&
                        store.claimDailyExecution(automation.id, today, scheduledAtMillis)
                    ) {
                        val result = executor.run(automation)
                        if (!result.skipped) store.recordExecution(automation.id, result)
                    }
                }
        }.fold(
            onSuccess = { Result.success() },
            onFailure = {
                if (it is kotlinx.coroutines.CancellationException) throw it
                Result.retry()
            },
        )
    }
}
