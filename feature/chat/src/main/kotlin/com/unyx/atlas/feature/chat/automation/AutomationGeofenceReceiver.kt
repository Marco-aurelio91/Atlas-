package com.unyx.atlas.feature.chat.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AutomationGeofenceReceiver : BroadcastReceiver() {
    @Inject lateinit var store: AutomationStore
    @Inject lateinit var executor: AutomationExecutor

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val entering = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> true
            Geofence.GEOFENCE_TRANSITION_EXIT -> false
            else -> return
        }
        val ids = event.triggeringGeofences.orEmpty().map { it.requestId }.toSet()
        if (ids.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                store.all().filter { automation ->
                    automation.enabled && automation.id in ids &&
                        (automation.trigger as? AutomationTrigger.Geofence)?.entering == entering
                }.forEach { automation ->
                    val result = executor.run(automation)
                    if (!result.skipped) store.recordExecution(automation.id, result)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e(TAG, "Falha ao executar automação de geofence", failure)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "AtlasAutomation"
    }
}
