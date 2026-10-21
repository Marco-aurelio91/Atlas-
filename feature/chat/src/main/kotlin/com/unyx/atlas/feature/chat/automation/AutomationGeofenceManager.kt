package com.unyx.atlas.feature.chat.automation

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps enabled local location routines registered with Android Play services. */
@Singleton
class AutomationGeofenceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: AutomationStore,
) {
    private val client by lazy { LocationServices.getGeofencingClient(context) }

    fun sync() {
        if (!hasLocationPermissions()) return
        val pendingIntent = geofencePendingIntent()
        val geofences = store.all().filter { it.enabled }
            .mapNotNull { automation ->
                val trigger = automation.trigger as? AutomationTrigger.Geofence ?: return@mapNotNull null
                Geofence.Builder()
                    .setRequestId(automation.id)
                    .setCircularRegion(trigger.latitude, trigger.longitude, trigger.radiusMeters)
                    .setTransitionTypes(if (trigger.entering) Geofence.GEOFENCE_TRANSITION_ENTER else Geofence.GEOFENCE_TRANSITION_EXIT)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .build()
            }

        // Re-register as a set so edits, pauses, and deletions remove stale Android registrations.
        runCatching {
            client.removeGeofences(pendingIntent).addOnCompleteListener {
                if (geofences.isEmpty()) return@addOnCompleteListener
                val request = GeofencingRequest.Builder()
                    .setInitialTrigger(0)
                    .addGeofences(geofences)
                    .build()
                client.addGeofences(request, pendingIntent)
            }
        }
    }

    @Suppress("MissingPermission")
    private fun hasLocationPermissions(): Boolean {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine && background
    }

    private fun geofencePendingIntent(): PendingIntent {
        val intent = Intent(context, AutomationGeofenceReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, GEOFENCE_REQUEST_CODE, intent, flags)
    }

    companion object {
        private const val GEOFENCE_REQUEST_CODE = 3417
    }
}
