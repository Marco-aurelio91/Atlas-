package com.unyx.atlas.feature.chat.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Emits headphone connection changes while the launcher process is alive. Charger broadcasts are
 * handled by [AutomationPowerReceiver], which also works when Android has stopped this process.
 * The daily-time trigger is polled by [AutomationWorker] instead, since WorkManager has no
 * equivalent of "wake me on this broadcast". Runtime-registered
 * rather than a manifest `<receiver>`, mirroring
 * [com.unyx.atlas.launcher.system.BatteryMonitor]'s existing pattern in this codebase — reliable
 * here specifically because this app IS the device's HOME launcher, which Android keeps resident
 * far more aggressively than an ordinary background app; a manifest receiver would survive process
 * death too, but adds new exported-receiver surface for a benefit this process's own lifetime
 * characteristics already mostly cover.
 */
@Singleton
class AutomationTriggerMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val triggers: Flow<AutomationTrigger> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_HEADSET_PLUG -> {
                        // state extra: 0 = unplugged, 1 = plugged (per AudioManager's documented contract).
                        when (intent.getIntExtra("state", -1)) {
                            0 -> trySend(AutomationTrigger.HeadphonesConnected(false))
                            1 -> trySend(AutomationTrigger.HeadphonesConnected(true))
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_HEADSET_PLUG)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }
}
