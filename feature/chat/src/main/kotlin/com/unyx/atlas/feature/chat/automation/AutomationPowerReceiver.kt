package com.unyx.atlas.feature.chat.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/** System power broadcasts wake this receiver even when Android has reclaimed the launcher process. */
@AndroidEntryPoint
class AutomationPowerReceiver : BroadcastReceiver() {
    @Inject lateinit var store: AutomationStore
    @Inject lateinit var executor: AutomationExecutor

    override fun onReceive(context: Context, intent: Intent) {
        val trigger = when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> AutomationTrigger.ChargerConnected(true)
            Intent.ACTION_POWER_DISCONNECTED -> AutomationTrigger.ChargerConnected(false)
            Intent.ACTION_BATTERY_LOW -> AutomationTrigger.BatteryLow
            else -> return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching {
                    store.all().filter { it.enabled && it.trigger == trigger }.forEach { automation ->
                        val result = executor.run(automation)
                        if (!result.skipped) store.recordExecution(automation.id, result)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
