package com.unyx.atlas.athena

import android.content.Context
import androidx.work.*
import com.unyx.athena.core.*
import com.unyx.athena.runtime.WorldRuntime
import com.unyx.atlas.data.di.ApplicationScope
import com.unyx.atlas.data.smartspace.WeatherRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AthenaPreferences(val weatherUntil: Long = 0, val githubUntil: Long = 0)

/**
 * ATHENA has no UI, screen, or plugin identity of its own (2026-09-30 architecture correction) —
 * it is the perception layer ATLAS queries, not a second assistant. This coordinator is ATLAS's
 * own glue: it owns the *personal* opt-in preference ("what am I allowed to check, for how long")
 * and exposes [message]/[runtime] for ATLAS's own Settings screen to render — never a dedicated
 * Athena activity/plugin. Deciding whether/how to surface a [com.unyx.athena.core.WorldEvent] to
 * the user (Island, notification, chat) is a *presentation* concern that belongs to ATLAS's own
 * surfaces when it's rebuilt properly; this class intentionally does not do that anymore (it used
 * to, via a dedicated Island plugin, which was exactly the "body" the correction removed).
 */
@Singleton
class AthenaCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    val runtime: WorldRuntime,
    private val weather: WeatherRepository,
) {
    private val prefs = context.getSharedPreferences("athena-atlas-preferences", Context.MODE_PRIVATE)
    private val mutablePreferences = MutableStateFlow(AthenaPreferences(prefs.getLong("weatherUntil", 0), prefs.getLong("githubUntil", 0)))
    val preferences = mutablePreferences.asStateFlow()
    private val mutableMessage = MutableStateFlow("Pronto para consultar")
    val message = mutableMessage.asStateFlow()
    private val configurationLock = Mutex()
    private val passLock = Mutex()
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            guarded { runtime.maintain(); schedule() }
            while (isActive) {
                guarded { runtime.maintain(); schedule() }
                delay(30_000)
            }
        }
    }

    suspend fun configure(weatherEnabled: Boolean? = null, githubEnabled: Boolean? = null) {
        withContext(Dispatchers.IO) { configurationLock.withLock {
            val old = preferences.value
            val until = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(24)
            val next = old.copy(
                weatherUntil = weatherEnabled?.let { if (it) until else 0 } ?: old.weatherUntil,
                githubUntil = githubEnabled?.let { if (it) until else 0 } ?: old.githubUntil,
            )
            check(prefs.edit().putLong("weatherUntil", next.weatherUntil).putLong("githubUntil", next.githubUntil).commit())
            mutablePreferences.value = next
            if (next.weatherUntil <= System.currentTimeMillis()) runtime.cancel("weather")
            if (next.githubUntil <= System.currentTimeMillis()) runtime.cancel("github")
            schedule()
        } }
        if (weatherEnabled == true || githubEnabled == true) refresh()
    }

    suspend fun refresh() = withContext(Dispatchers.IO) { passLock.withLock {
        guarded {
            mutableMessage.value = "Consultando fontes…"
            val current = if (preferences.value.weatherUntil > System.currentTimeMillis()) weather.getFresh() else null
            // Never hold the preference lock during network calls. A pause can take effect
            // immediately, and a late result cannot recreate a cancelled subscription.
            configurationLock.withLock {
                val settings = preferences.value
                val now = System.currentTimeMillis()
                val region = weather.currentRegion()
                if (settings.weatherUntil > now && current != null && region != null && weather.hasPermission()) {
                    runtime.subscribe(WorldSubscription("weather", WorldSelector.Weather(region), Instant.ofEpochMilli(settings.weatherUntil)))
                } else runtime.cancel("weather")
                if (settings.githubUntil > now) runtime.subscribe(WorldSubscription("github", com.unyx.athena.status.GitHubStatusConnector.SELECTOR, Instant.ofEpochMilli(settings.githubUntil)))
                else runtime.cancel("github")
            }
            val replies = runtime.poll()
            val settings = preferences.value
            val now = System.currentTimeMillis()
            mutableMessage.value = when {
                settings.weatherUntil > now && !weather.hasPermission() -> "Clima precisa da permissão de localização."
                settings.weatherUntil > now && runtime.subscriptions.value.none { it.id == "weather" } -> "Localização ou clima indisponível."
                replies.any { it is WorldReply.Failure } -> "Uma fonte está indisponível. Veja o diagnóstico."
                replies.isEmpty() -> "Acompanhamento pausado"
                else -> "Fontes atualizadas"
            }
            schedule()
        }
    } }

    private suspend fun guarded(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutableMessage.value = "Não foi possível concluir. Tente novamente." }
    }

    private fun schedule() {
        val p = preferences.value
        val active = maxOf(p.weatherUntil, p.githubUntil) > System.currentTimeMillis()
        val manager = WorkManager.getInstance(context)
        if (!active) manager.cancelUniqueWork(WORK_NAME)
        else manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AthenaWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()).build())
    }
    companion object { const val WORK_NAME = "athena-world-monitor" }
}
