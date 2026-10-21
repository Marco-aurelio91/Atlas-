package com.unyx.atlas.feature.chat.automation

import org.json.JSONArray
import org.json.JSONObject

/**
 * A user-defined "quando X, faça Y" automation — the real-architecture translation of the original
 * master plan's visual IFTTT builder (ATLAS_Plano_de_Desenvolvimento.md section 9.2): same
 * trigger→condition→action shape, but created via chat tools instead of a dedicated builder screen
 * (matching how everything else in this app is authored) and stored purely locally, never in
 * Supabase — a trigger has to still fire with no network connectivity, so the source of truth has
 * to live on-device.
 */
data class Automation(
    val id: String,
    val label: String,
    val trigger: AutomationTrigger,
    val action: AutomationAction,
    val condition: AutomationCondition? = null,
    val enabled: Boolean = true,
    /** Earliest instant on which this enabled version of the routine may run. */
    val enabledSinceMillis: Long = System.currentTimeMillis(),
    /** "YYYY-MM-DD" of the last day this fired — only meaningful for [AutomationTrigger.DailyTime]
     * (see AutomationWorker), which polls every 15 minutes and needs this to fire once, not once
     * per poll. Event-driven triggers (charger/headphones) don't use this. */
    val lastFiredDate: String? = null,
    /** Last attempted execution, persisted locally for the routines screen. */
    val lastExecutionAt: Long? = null,
    val lastExecutionSuccess: Boolean? = null,
    val lastExecutionMessage: String? = null,
)

sealed class AutomationTrigger {
    /** [connected]=true fires when the charger is plugged in, false when unplugged. */
    data class ChargerConnected(val connected: Boolean) : AutomationTrigger()
    /** [connected]=true fires when headphones/a headset connect, false when they disconnect. */
    data class HeadphonesConnected(val connected: Boolean) : AutomationTrigger()
    /** Android's system-defined low-battery transition; it fires once until the battery recovers. */
    data object BatteryLow : AutomationTrigger()
    /** Fires once a day at [hour]:[minute] (device local time) — polled every 15 minutes by
     * AutomationWorker rather than an exact alarm, so system delays can move execution later in
     * the day. Acceptable for a daily routine; not for anything time-critical. */
    data class DailyTime(
        val hour: Int,
        val minute: Int,
        /** Empty means every day; otherwise ISO day numbers (Monday=1 … Sunday=7). */
        val daysOfWeek: Set<Int> = emptySet(),
    ) : AutomationTrigger()
    /** Enters or exits a saved circular area. Coordinates stay in the encrypted local store. */
    data class Geofence(
        val placeName: String,
        val latitude: Double,
        val longitude: Double,
        val radiusMeters: Float,
        val entering: Boolean,
    ) : AutomationTrigger()
}

sealed class AutomationAction {
    data class OpenApp(val appLabel: String) : AutomationAction()
    data class SetVolume(val level: Int, val stream: String) : AutomationAction()
    data class SetBrightness(val level: Int) : AutomationAction()
    data class ShowNotification(val text: String) : AutomationAction()
}

/** Optional guard evaluated at trigger time, on top of the trigger itself — e.g. "só depois das
 * 22h" for a charger-connected trigger (the plan doc's "Manhã produtiva" example: charger connected
 * after 22h → silence notifications, set alarm). Both bounds optional; a null bound means
 * unrestricted on that side. A window crossing midnight (afterHour=22, beforeHour=6) is treated as
 * "after OR before", not "after AND before" — otherwise it would never match anything. */
data class AutomationCondition(val afterHour: Int? = null, val beforeHour: Int? = null) {
    fun matches(hour: Int): Boolean {
        if (afterHour != null && beforeHour != null && afterHour > beforeHour) {
            return hour >= afterHour || hour < beforeHour
        }
        val afterOk = afterHour == null || hour >= afterHour
        val beforeOk = beforeHour == null || hour < beforeHour
        return afterOk && beforeOk
    }
}

// ---- JSON (de)serialization — manual org.json mapping, matching core/memory/MemoryJson.kt's style ----

fun Automation.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("label", label)
    put("enabled", enabled)
    put("trigger", trigger.toJson())
    put("action", action.toJson())
    condition?.let { put("condition", it.toJson()) }
    put("enabled_since_millis", enabledSinceMillis)
    lastFiredDate?.let { put("last_fired_date", it) }
    lastExecutionAt?.let { put("last_execution_at", it) }
    lastExecutionSuccess?.let { put("last_execution_success", it) }
    lastExecutionMessage?.let { put("last_execution_message", it) }
}

fun JSONObject.toAutomation(): Automation = Automation(
    id = getString("id"),
    label = getString("label"),
    enabled = optBoolean("enabled", true),
    trigger = getJSONObject("trigger").toAutomationTrigger(),
    action = getJSONObject("action").toAutomationAction(),
    condition = if (has("condition") && !isNull("condition")) getJSONObject("condition").toAutomationCondition() else null,
    enabledSinceMillis = optLong("enabled_since_millis", System.currentTimeMillis()),
    lastFiredDate = if (has("last_fired_date")) getString("last_fired_date") else null,
    lastExecutionAt = if (has("last_execution_at")) optLong("last_execution_at") else null,
    lastExecutionSuccess = if (has("last_execution_success") && !isNull("last_execution_success")) getBoolean("last_execution_success") else null,
    lastExecutionMessage = optString("last_execution_message").ifBlank { null },
)

private fun AutomationTrigger.toJson(): JSONObject = JSONObject().apply {
    when (val t = this@toJson) {
        is AutomationTrigger.ChargerConnected -> { put("type", "charger"); put("connected", t.connected) }
        is AutomationTrigger.HeadphonesConnected -> { put("type", "headphones"); put("connected", t.connected) }
        is AutomationTrigger.BatteryLow -> put("type", "battery_low")
        is AutomationTrigger.DailyTime -> {
            put("type", "daily_time"); put("hour", t.hour); put("minute", t.minute)
            put("days_of_week", JSONArray(t.daysOfWeek.sorted()))
        }
        is AutomationTrigger.Geofence -> {
            put("type", "geofence"); put("place_name", t.placeName)
            put("latitude", t.latitude); put("longitude", t.longitude)
            put("radius_meters", t.radiusMeters.toDouble()); put("entering", t.entering)
        }
    }
}

private fun JSONObject.toAutomationTrigger(): AutomationTrigger = when (getString("type")) {
    "charger" -> AutomationTrigger.ChargerConnected(getBoolean("connected"))
    "headphones" -> AutomationTrigger.HeadphonesConnected(getBoolean("connected"))
    "battery_low" -> AutomationTrigger.BatteryLow
    "daily_time" -> AutomationTrigger.DailyTime(
        getInt("hour"), getInt("minute"),
        optJSONArray("days_of_week")?.let { days ->
            buildSet { for (index in 0 until days.length()) days.optInt(index, -1).takeIf { it in 1..7 }?.let(::add) }
        }.orEmpty(),
    )
    "geofence" -> AutomationTrigger.Geofence(
        getString("place_name"), getDouble("latitude"), getDouble("longitude"),
        getDouble("radius_meters").toFloat(), getBoolean("entering"),
    )
    else -> error("tipo de gatilho desconhecido: ${getString("type")}")
}

private fun AutomationAction.toJson(): JSONObject = JSONObject().apply {
    when (val a = this@toJson) {
        is AutomationAction.OpenApp -> { put("type", "open_app"); put("app_label", a.appLabel) }
        is AutomationAction.SetVolume -> { put("type", "set_volume"); put("level", a.level); put("stream", a.stream) }
        is AutomationAction.SetBrightness -> { put("type", "set_brightness"); put("level", a.level) }
        is AutomationAction.ShowNotification -> { put("type", "show_notification"); put("text", a.text) }
    }
}

private fun JSONObject.toAutomationAction(): AutomationAction = when (getString("type")) {
    "open_app" -> AutomationAction.OpenApp(getString("app_label"))
    "set_volume" -> AutomationAction.SetVolume(getInt("level"), optString("stream").ifBlank { "media" })
    "set_brightness" -> AutomationAction.SetBrightness(getInt("level"))
    "show_notification" -> AutomationAction.ShowNotification(getString("text"))
    else -> error("tipo de ação desconhecido: ${getString("type")}")
}

private fun AutomationCondition.toJson(): JSONObject = JSONObject().apply {
    afterHour?.let { put("after_hour", it) }
    beforeHour?.let { put("before_hour", it) }
}

private fun JSONObject.toAutomationCondition(): AutomationCondition = AutomationCondition(
    afterHour = if (has("after_hour")) getInt("after_hour") else null,
    beforeHour = if (has("before_hour")) getInt("before_hour") else null,
)

fun List<Automation>.toJsonArray(): JSONArray = JSONArray().apply { forEach { put(it.toJson()) } }

fun JSONArray.toAutomationList(): List<Automation> = List(length()) { getJSONObject(it).toAutomation() }
