package com.unyx.atlas.feature.chat.automation

import android.content.Context
import com.unyx.atlas.security.AtlasSecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local-only persistence for [Automation]s — plain SharedPreferences holding one JSON array,
 * using encrypted app-private preferences. See
 * [Automation]'s doc for why this is local instead of Supabase.
 *
 * Goes through [AtlasSecurePreferences] rather than building `EncryptedSharedPreferences` directly
 * so a device whose Keystore is unavailable degrades to a process-memory store (matching every
 * other secure store in the app) instead of throwing out of this constructor — [AutomationStore]
 * is a Hilt singleton, so an uncaught exception here would have taken the whole DI graph down the
 * first time anything touched automations. The legacy-key migration (older releases' plaintext
 * `atlas_automations` prefs) is handled generically by [AtlasSecurePreferences.open] since its key
 * name already matches [KEY].
 */
@Singleton
class AutomationStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = AtlasSecurePreferences.open(
        context = context,
        secureName = PREFS,
        legacyPlainName = LEGACY_PREFS,
    ).preferences

    @Synchronized
    fun all(): List<Automation> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            val automations = array.toAutomationList()
            if ((0 until array.length()).any { !array.getJSONObject(it).has("enabled_since_millis") }) {
                prefs.edit().putString(KEY, automations.toJsonArray().toString()).apply()
            }
            automations
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(automation: Automation) {
        save(all() + automation)
    }

    @Synchronized
    fun remove(id: String) {
        save(all().filterNot { it.id == id })
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) {
        save(all().map {
            if (it.id == id) it.copy(
                enabled = enabled,
                enabledSinceMillis = if (enabled) System.currentTimeMillis() else it.enabledSinceMillis,
            ) else it
        })
    }

    @Synchronized
    fun update(updated: Automation) {
        save(all().map {
            if (it.id == updated.id) updated.copy(enabledSinceMillis = System.currentTimeMillis()) else it
        })
    }

    @Synchronized
    fun recordExecution(id: String, result: AutomationExecutionResult, at: Long = System.currentTimeMillis()) {
        save(all().map {
            if (it.id == id) it.copy(
                lastExecutionAt = at,
                lastExecutionSuccess = result.success,
                lastExecutionMessage = result.message,
            ) else it
        })
    }

    @Synchronized
    fun claimDailyExecution(id: String, date: String, scheduledAtMillis: Long): Boolean {
        val automations = all()
        val target = automations.firstOrNull { it.id == id && it.enabled } ?: return false
        if (target.lastFiredDate == date || target.enabledSinceMillis > scheduledAtMillis) return false
        save(automations.map { if (it.id == id) it.copy(lastFiredDate = date) else it })
        return true
    }

    private fun save(list: List<Automation>) {
        prefs.edit().putString(KEY, list.toJsonArray().toString()).apply()
    }

    companion object {
        private const val PREFS = "atlas_automations_secure"
        private const val KEY = "automations"
        private const val LEGACY_PREFS = "atlas_automations"
        fun newId(): String = UUID.randomUUID().toString()
    }
}
