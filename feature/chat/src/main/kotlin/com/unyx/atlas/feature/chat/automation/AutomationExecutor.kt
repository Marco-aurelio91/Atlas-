package com.unyx.atlas.feature.chat.automation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.unyx.atlas.data.AppRepository
import com.unyx.atlas.data.SettingsRepository
import com.unyx.atlas.feature.chat.AtlasOverlayService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

data class AutomationExecutionResult(val success: Boolean, val message: String, val skipped: Boolean = false)

/**
 * Runs one [Automation]'s action from a non-UI context (the event-driven
 * [AutomationTriggerMonitor] collector or the periodic [AutomationWorker]) — neither has a
 * ChatViewModel around, so this deliberately reimplements open_app/set_volume/set_brightness as
 * small, self-contained bodies rather than reaching into ChatViewModel's executeTool(), which is
 * scoped to a Hilt ViewModel and Compose's coroutine lifecycle. The three bodies mirror
 * ChatViewModel's tool logic closely on purpose (same Android APIs, same behavior) — worth keeping
 * both in sync if either changes.
 */
@Singleton
class AutomationExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appRepository: AppRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun run(automation: Automation): AutomationExecutionResult {
        if (!automation.enabled) return AutomationExecutionResult(false, "Rotina desativada.", skipped = true)
        val hour = java.time.LocalTime.now().hour
        if (automation.condition?.matches(hour) == false) return AutomationExecutionResult(false, "Fora do horário definido.", skipped = true)
        val result = try {
            when (val action = automation.action) {
                is AutomationAction.OpenApp -> openApp(action.appLabel)
                is AutomationAction.SetVolume -> setVolume(action.level, action.stream)
                is AutomationAction.SetBrightness -> setBrightness(action.level)
                is AutomationAction.ShowNotification -> showNotification(automation.label, action.text)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AutomationExecutionResult(false, error.message ?: "O Android não permitiu concluir a ação.")
        }
        if (!result.skipped) notifyOverlayIfEnabled()
        return result
    }

    private suspend fun notifyOverlayIfEnabled() {
        try {
            if (settingsRepository.overlayEnabled.first() && Settings.canDrawOverlays(context)) {
                AtlasOverlayService.showAutomationEvent(context)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "Could not notify the Atlas overlay about an automation.", failure)
        }
    }

    private suspend fun openApp(appLabel: String): AutomationExecutionResult {
        val query = appLabel.normalizedForMatch()
        val apps = runCatching { appRepository.apps.first() }.getOrDefault(emptyList())
        val app = apps.firstOrNull { it.label.normalizedForMatch() == query }
            ?: apps
                .filter { it.label.normalizedForMatch().let { l -> l.contains(query) || query.contains(l) } }
                .minByOrNull { it.label.length }
            ?: return AutomationExecutionResult(false, "Não encontrei o app $appLabel.")
        return appRepository.launch(app).fold(
            onSuccess = { AutomationExecutionResult(true, "Abri ${app.label}.") },
            onFailure = { AutomationExecutionResult(false, "Não consegui abrir ${app.label}.") },
        )
    }

    private fun setVolume(level: Int, stream: String): AutomationExecutionResult = runCatching {
        val streamType = when (stream.lowercase()) {
            "alarme", "alarm" -> AudioManager.STREAM_ALARM
            "chamada", "call", "ligacao" -> AudioManager.STREAM_VOICE_CALL
            "toque", "ring" -> AudioManager.STREAM_RING
            else -> AudioManager.STREAM_MUSIC
        }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audioManager.getStreamMaxVolume(streamType)
        val target = (level / 100.0 * max).roundToInt().coerceIn(0, max)
        audioManager.setStreamVolume(streamType, target, 0)
    }.fold(
        onSuccess = { AutomationExecutionResult(true, "Volume ajustado para $level%.") },
        onFailure = { AutomationExecutionResult(false, "O Android não permitiu ajustar o volume.") },
    )

    private fun setBrightness(level: Int): AutomationExecutionResult {
        if (!Settings.System.canWrite(context)) return AutomationExecutionResult(false, "Permissão de ajuste de brilho não concedida.")
        val target = (level / 100.0 * 255).roundToInt().coerceIn(0, 255)
        return runCatching {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                target,
            ).also { changed ->
                check(changed && Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) == target)
            }
        }.fold(
            onSuccess = { AutomationExecutionResult(true, "Brilho ajustado para $level%.") },
            onFailure = { AutomationExecutionResult(false, "O Android não permitiu ajustar o brilho.") },
        )
    }

    /** Mirrors feature:updater's UpdateNotifier (same permission check, same channel-per-call-safe
     * createNotificationChannel pattern) — a separate channel ("routines") since these are a
     * different kind of notification the user may want to mute independently of update alerts. */
    private fun showNotification(title: String, text: String): AutomationExecutionResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return AutomationExecutionResult(false, "Permissão de notificações não concedida.")
        }
        return runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rotinas", NotificationManager.IMPORTANCE_DEFAULT))
            val notif = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(title.hashCode(), notif)
        }.fold(
            onSuccess = { AutomationExecutionResult(true, "Notificação exibida.") },
            onFailure = { AutomationExecutionResult(false, "Não foi possível exibir a notificação.") },
        )
    }

    private fun String.normalizedForMatch(): String = lowercase().filter { it.isLetterOrDigit() }

    companion object {
        private const val CHANNEL = "routines"
        private const val TAG = "AutomationExecutor"
    }
}
