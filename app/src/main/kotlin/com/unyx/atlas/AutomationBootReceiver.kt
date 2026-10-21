package com.unyx.atlas

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.unyx.atlas.feature.chat.automation.AutomationGeofenceManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AutomationBootReceiver : BroadcastReceiver() {
    @Inject lateinit var geofenceManager: AutomationGeofenceManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) geofenceManager.sync()
    }
}
