package com.unyx.atlas

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.unyx.atlas.data.SettingsRepository
import com.unyx.atlas.feature.chat.automation.AutomationExecutor
import com.unyx.atlas.feature.chat.automation.AutomationGeofenceManager
import com.unyx.atlas.feature.chat.automation.AutomationScheduler
import com.unyx.atlas.feature.chat.automation.AutomationStore
import com.unyx.atlas.feature.chat.automation.AutomationTriggerMonitor
import com.unyx.atlas.feature.chat.ProactiveNudgeWorker
import com.unyx.atlas.feature.connect.notifications.ChatNotificationScheduler
import com.unyx.atlas.feature.updater.UpdateScheduler
import com.unyx.atlas.feature.updater.isReleaseBuild
import javax.inject.Inject

@HiltAndroidApp
class LauncherApplication : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        athenaCoordinator.start()

        // Rotinas Visuais (local automations) — see feature:chat's automation package. Headphone
        // events are a process-lifetime collector; charger events use a manifest receiver so Android
        // can wake the app for them. This collector uses the same discipline
        // as isReleaseBuild's guard below: a root coroutine on every HOME process start must never
        // let an uncaught exception crash-loop the launcher itself. The daily-time half schedules
        // unconditionally regardless of build type (unlike the update checker, this has nothing to
        // do with app distribution).
        AutomationScheduler.schedule(this)
        automationGeofenceManager.sync()
        // Atlas Connect's own background poll (plan §15) — same unconditional-at-startup
        // reasoning as AutomationScheduler just above: an idle poll with no pairings yet costs
        // nothing worth branching on.
        if (BuildConfig.ATLAS_CONNECT_ENABLED) ChatNotificationScheduler.schedule(this) else ChatNotificationScheduler.cancel(this)
        // Deadline nudges as system notifications while the chat is closed — same unconditional
        // scheduling; the worker no-ops when signed out or notifications are off.
        ProactiveNudgeWorker.schedule(this)
        CoroutineScope(Dispatchers.Default).launch {
            runCatching {
                automationTriggerMonitor.triggers.collect { trigger ->
                    runCatching {
                        automationStore.all()
                            .filter { it.enabled && it.trigger == trigger }
                            .forEach { automation ->
                                val result = automationExecutor.run(automation)
                                if (!result.skipped) automationStore.recordExecution(automation.id, result)
                            }
                    }
                }
            }
        }

        if (isReleaseBuild(this)) {
            // Schedule the periodic update check only when auto-update is enabled; cancel it otherwise.
            // (Reads the persisted setting off the main thread; the worker also double-checks the flag.)
            // runCatching: this is a root coroutine on every process start of the HOME app — an
            // uncaught DataStore IOException or WorkManager init error here would kill the process
            // again on every launch, a genuine HOME crash loop. Losing one scheduling pass is fine;
            // the next process start (or the settings toggle) reschedules.
            CoroutineScope(Dispatchers.Default).launch {
                runCatching {
                    if (settingsRepository.autoUpdateEnabledOnce()) {
                        UpdateScheduler.schedule(this@LauncherApplication)
                    } else {
                        UpdateScheduler.cancel(this@LauncherApplication)
                    }
                }
            }
        }
    }

    /** Hilt-provided ImageLoader with the app-icon fetcher/keyer (see DataModule). */
    @Inject
    lateinit var imageLoader: ImageLoader

    /** Reads the auto-update toggle at startup to decide whether to schedule the periodic check. */
    @Inject
    lateinit var settingsRepository: SettingsRepository

    /** Hilt-provided WorkerFactory so [@HiltWorker] workers receive injected dependencies. */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var automationTriggerMonitor: AutomationTriggerMonitor

    @Inject
    lateinit var automationStore: AutomationStore

    @Inject
    lateinit var automationExecutor: AutomationExecutor

    @Inject
    lateinit var automationGeofenceManager: AutomationGeofenceManager

    @Inject
    lateinit var athenaCoordinator: com.unyx.atlas.athena.AthenaCoordinator

    /** Supplies the custom [Configuration] that wires Hilt's worker factory into WorkManager. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader
}
