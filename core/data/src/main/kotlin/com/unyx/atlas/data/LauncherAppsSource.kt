package com.unyx.atlas.data

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.unyx.atlas.model.AppItem
import com.unyx.atlas.model.IconEpochs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps [LauncherApps]: streams the installed launchable apps (reacting to install/remove/change)
 * and launches them — the data source behind Atlas's `open_app` chat tool.
 */
@Singleton
class LauncherAppsSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    /** Package names from LauncherApps callbacks — lets feature-layer caches (e.g. resolved pinned
     *  shortcuts) invalidate per package instead of guessing from full app-list reloads. */
    private val _packageEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val packageEvents: SharedFlow<String> = _packageEvents

    /** (package, profile serial) from onPackageRemoved — a DEFINITIVE uninstall for that profile.
     *  Updates fire onPackageChanged and storage ejection onPackagesUnavailable, so neither can
     *  reach this flow and trigger a wrongful home-row cleanup. */
    private val _packageRemovals = MutableSharedFlow<Pair<String, Long>>(extraBufferCapacity = 16)
    val packageRemovals: SharedFlow<Pair<String, Long>> = _packageRemovals

    /** Re-fetch tokens for app icons (see [IconEpochs]): a package update bumps that package, an
     *  icon-pack content change bumps the global generation. AppIcon feeds these into the Coil
     *  model/cache key, so even icons already on screen re-fetch immediately. */
    private val _iconEpochs = MutableStateFlow(IconEpochs())
    val iconEpochs: StateFlow<IconEpochs> = _iconEpochs

    /** Fail-safe install check for the ghost-row sweep: only a definitive "name not found" (or a
     *  removed profile) answers false — any other failure keeps the row (a locked work profile or
     *  a transient Binder error must never wipe real items). */
    fun isAppInstalled(packageName: String, userSerial: Long): Boolean {
        val user = runCatching { userManager?.getUserForSerialNumber(userSerial) }.getOrNull()
            ?: return false // the whole profile is gone → its rows are stale
        val result = runCatching { launcherApps.getApplicationInfo(packageName, 0, user) }
        return result.isSuccess || result.exceptionOrNull() !is PackageManager.NameNotFoundException
    }

    fun appsFlow(): Flow<List<AppItem>> = callbackFlow {
        val handler = Handler(Looper.getMainLooper())

        // One serial worker fed by a conflated trigger, NOT a coroutine per event: overlapping
        // queryApps() calls could complete out of order, leaving the flow's latest value a stale
        // snapshot after a burst of package events (install storms, batch updates). Serializing
        // guarantees the newest scan is emitted last; conflation coalesces the burst into one rescan.
        val reloads = Channel<Unit>(Channel.CONFLATED)
        fun reload() { reloads.trySend(Unit) }
        launch(Dispatchers.IO) {
            for (unused in reloads) trySend(queryApps())
        }

        fun packageEvent(vararg packageNames: String, iconsMayHaveChanged: Boolean = false) {
            packageNames.forEach { _packageEvents.tryEmit(it) }
            // An updated/replaced package bumps its icon epoch: the epoch is in the Coil model/cache
            // key, so the changed app's icon re-fetches everywhere — including AsyncImages already on
            // screen (dock, open home page, drawer top), which only re-launch when the model changes.
            // A memory-cache clear alone left those painters showing the OLD icon until process death.
            if (iconsMayHaveChanged) {
                _iconEpochs.update { it.bump(packageNames.asIterable()) }
            }
            reload()
        }

        val callback = object : LauncherApps.Callback() {
            override fun onPackageAdded(packageName: String, user: UserHandle) = packageEvent(packageName)
            override fun onPackageRemoved(packageName: String, user: UserHandle) {
                val serial = runCatching { userManager?.getSerialNumberForUser(user) }.getOrNull() ?: 0L
                _packageRemovals.tryEmit(packageName to serial)
                packageEvent(packageName)
            }
            override fun onPackageChanged(packageName: String, user: UserHandle) =
                packageEvent(packageName, iconsMayHaveChanged = true)
            override fun onPackagesAvailable(names: Array<out String>, user: UserHandle, replacing: Boolean) =
                packageEvent(*names, iconsMayHaveChanged = replacing)
            override fun onPackagesUnavailable(names: Array<out String>, user: UserHandle, replacing: Boolean) =
                packageEvent(*names)
        }

        launcherApps.registerCallback(callback, handler)
        reload()
        awaitClose { launcherApps.unregisterCallback(callback) }
    }

    private fun queryApps(): List<AppItem> {
        val profiles = runCatching { userManager?.userProfiles }.getOrNull() ?: listOf(Process.myUserHandle())
        return profiles.flatMap { user ->
            val serial = runCatching { userManager?.getSerialNumberForUser(user) }.getOrNull() ?: 0L
            val list = runCatching {
                launcherApps.getActivityList(null, user)
            }.getOrElse { emptyList() }
            list.map { info ->
                AppItem(
                    packageName = info.componentName.packageName,
                    className = info.componentName.className,
                    user = user,
                    userSerial = serial,
                    label = info.label?.toString().orEmpty(),
                )
            }
        }.sortedBy { it.label.lowercase() }
    }

    /**
     * Launches [appItem]. A launcher is the device's HOME, so a single failed launch (app removed
     * mid-tap, profile locked, activity no longer launchable) must never crash the process — the
     * error is captured in the [Result] for the caller to log or surface.
     */
    fun launch(appItem: AppItem): Result<Unit> = runCatching {
        launcherApps.startMainActivity(appItem.componentName, appItem.user, null, null)
    }

}
