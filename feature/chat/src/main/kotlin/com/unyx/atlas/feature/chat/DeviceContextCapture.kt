package com.unyx.atlas.feature.chat

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager

/** A snapshot of "what's true right now" on the device — the on-demand version of the design
 * spec's item 34 context inspection screen. Deliberately capture-on-demand (read when the user
 * opens [ContextScreen], not a background job): a periodic capture service is a much bigger
 * subsystem (needs a scheduler, battery-cost tradeoffs, its own lifecycle) than this feature
 * actually calls for yet — see the design spec memory for why this was scoped down from "capture
 * continuously" to "capture when asked". Uses only signals already available without a new
 * permission: the battery sticky broadcast (no permission at all) and `ConnectivityManager`
 * (`ACCESS_NETWORK_STATE`, already declared for the home status bar). No Wi-Fi SSID — that needs a
 * location permission on modern Android, too invasive for what this screen is for. */
data class DeviceContextSnapshot(
    val batteryPercent: Int?,
    val batteryCharging: Boolean?,
    val network: NetworkKind,
    val capturedAtMillis: Long,
)

enum class NetworkKind { WIFI, MOBILE_DATA, OTHER, NONE }

fun captureDeviceContext(context: Context): DeviceContextSnapshot {
    val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val batteryPercent = if (level >= 0 && scale > 0) (level * 100 / scale) else null
    val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val charging = if (status >= 0) {
        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    } else {
        null
    }

    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val capabilities = connectivityManager?.activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
    val network = when {
        capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkKind.NONE
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.MOBILE_DATA
        else -> NetworkKind.OTHER
    }

    return DeviceContextSnapshot(
        batteryPercent = batteryPercent,
        batteryCharging = charging,
        network = network,
        capturedAtMillis = System.currentTimeMillis(),
    )
}
