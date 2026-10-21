package com.unyx.atlas.feature.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import android.app.Service
import com.unyx.atlas.data.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class AtlasOverlayService : Service() {
    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val commandMutex = Mutex()
    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var eventText: TextView? = null
    private var atlasIconView: ImageView? = null
    private var activityState: AtlasActivity = AtlasActivity.IDLE
    private var hideEvent: Runnable? = null
    private var hiddenForAssistant = false
    private var hiddenForCapture = false
    private var assistantHideWatchdog: Runnable? = null
    private var captureHideWatchdog: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            disableOverlay()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_ASSISTANT_HIDE -> {
                hiddenForAssistant = true
                scheduleAssistantWatchdog()
            }
            ACTION_ASSISTANT_SHOW -> {
                hiddenForAssistant = false
                cancelAssistantWatchdog()
            }
            ACTION_CAPTURE_HIDE -> {
                hiddenForCapture = true
                scheduleCaptureWatchdog()
            }
            ACTION_CAPTURE_SHOW -> {
                hiddenForCapture = false
                cancelCaptureWatchdog()
            }
            // The Assistant screen fires this on every state change while it's open (listening,
            // thinking, speaking...) — treat it as a liveness heartbeat that extends the watchdog,
            // so a long-running turn never gets the bubble reappearing on top of the Assistant.
            ACTION_ACTIVITY -> if (hiddenForAssistant) scheduleAssistantWatchdog()
        }
        startAsForeground()
        val event = intent?.action?.let(::eventForAction)
        val requestedActivity = intent?.takeIf { it.action == ACTION_ACTIVITY }
            ?.getStringExtra(EXTRA_ACTIVITY)
            ?.let { runCatching { AtlasActivity.valueOf(it) }.getOrNull() }
        serviceScope.launch {
            // Serializes the async tail of concurrent onStartCommand calls (e.g. several rapid
            // ACTION_ACTIVITY updates while the Assistant screen is streaming a reply) so a slower
            // settings read from an earlier intent can never apply its state after a newer intent's
            // state already landed.
            commandMutex.withLock {
                val enabled = try {
                    settingsRepository.overlayEnabled.first()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.e(TAG, "Could not read Atlas overlay preference.", failure)
                    false
                }
                if (!enabled || !Settings.canDrawOverlays(this@AtlasOverlayService)) {
                    stopSelf(startId)
                    return@withLock
                }
                try {
                    if (overlayView == null) {
                        val saved = runCatching { settingsRepository.overlayPositionOnce() }.getOrNull()
                        ensureOverlay(saved?.first, saved?.second)
                    }
                    applyOverlayVisibility()
                    event?.let(::showEvent)
                    requestedActivity?.let(::setActivity)
                } catch (failure: Exception) {
                    Log.e(TAG, "Could not attach Atlas overlay window.", failure)
                    stopSelf(startId)
                }
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        reclampOverlayPosition()
    }

    override fun onDestroy() {
        hideEvent?.let(mainHandler::removeCallbacks)
        assistantHideWatchdog?.let(mainHandler::removeCallbacks)
        captureHideWatchdog?.let(mainHandler::removeCallbacks)
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (failure: IllegalArgumentException) {
                Log.w(TAG, "Atlas overlay view was already detached.", failure)
            }
        }
        overlayView = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    private fun scheduleAssistantWatchdog() {
        assistantHideWatchdog?.let(mainHandler::removeCallbacks)
        assistantHideWatchdog = Runnable {
            Log.w(TAG, "Atlas overlay stayed hidden for the Assistant without a heartbeat; restoring it.")
            hiddenForAssistant = false
            applyOverlayVisibility()
        }.also { mainHandler.postDelayed(it, ASSISTANT_HIDE_WATCHDOG_MILLIS) }
    }

    private fun cancelAssistantWatchdog() {
        assistantHideWatchdog?.let(mainHandler::removeCallbacks)
        assistantHideWatchdog = null
    }

    private fun scheduleCaptureWatchdog() {
        captureHideWatchdog?.let(mainHandler::removeCallbacks)
        captureHideWatchdog = Runnable {
            Log.w(TAG, "Atlas overlay stayed hidden for a screen capture longer than expected; restoring it.")
            hiddenForCapture = false
            applyOverlayVisibility()
        }.also { mainHandler.postDelayed(it, CAPTURE_HIDE_WATCHDOG_MILLIS) }
    }

    private fun cancelCaptureWatchdog() {
        captureHideWatchdog?.let(mainHandler::removeCallbacks)
        captureHideWatchdog = null
    }

    private fun reclampOverlayPosition() {
        val view = overlayView ?: return
        val layout = params ?: return
        // Wait a frame: right after a rotation/fold, resources.displayMetrics may not reflect the
        // new orientation yet.
        view.post {
            val maxX = (resources.displayMetrics.widthPixels - view.width).coerceAtLeast(0)
            val maxY = (resources.displayMetrics.heightPixels - view.height).coerceAtLeast(0)
            val clampedX = layout.x.coerceIn(0, maxX)
            val clampedY = layout.y.coerceIn(0, maxY)
            if (clampedX != layout.x || clampedY != layout.y) {
                layout.x = clampedX
                layout.y = clampedY
                try {
                    windowManager?.updateViewLayout(view, layout)
                } catch (failure: Exception) {
                    Log.w(TAG, "Could not reclamp the Atlas overlay after a configuration change.", failure)
                }
                persistOverlayPosition(clampedX, clampedY)
            }
        }
    }

    private fun persistOverlayPosition(x: Int, y: Int) {
        serviceScope.launch {
            runCatching { settingsRepository.setOverlayPosition(x, y) }
        }
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Always-expanded horizontal pill of circular icon buttons — the leading one brand-colored,
     *  the rest plain — replacing the old single bubble that expanded a vertical menu on tap. Every
     *  button doubles as a drag handle: [installDragHandler] tells a tap from a drag by touch slop,
     *  so moving the overlay works from any icon without a dedicated grip area. */
    private fun ensureOverlay(initialX: Int? = null, initialY: Int? = null) {
        if (overlayView != null) return
        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            contentDescription = "Atlas flutuante. Toque em um ícone; arraste para mover."
            isFocusable = true
        }
        val event = TextView(this).apply {
            visibility = View.GONE
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            maxLines = 2
            setPadding((10 * density).toInt(), (5 * density).toInt(), (10 * density).toInt(), (5 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14 * density
                setColor(0xF20A1420.toInt())
                setStroke((1 * density).toInt(), 0xFF23506A.toInt())
            }
        }
        eventText = event
        root.addView(
            event,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (6 * density).toInt()
            },
        )

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((6 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 32 * density
                setColor(0xE6121A24.toInt())
                setStroke((1 * density).toInt(), 0xFF23506A.toInt())
            }
        }
        root.addView(pill)

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = initialX ?: (12 * density).toInt()
            y = initialY ?: (120 * density).toInt()
        }

        atlasIconView = addPillButton(
            pill,
            root,
            layoutParams,
            icon = null,
            description = "Pergunte ao Atlas. ${activityLabel(activityState)}",
            colorful = true,
        ) { openAssistant(OVERLAY_ACTION_QUICK_ASSISTANT) }
        addPillButton(
            pill,
            root,
            layoutParams,
            icon = android.R.drawable.ic_menu_view,
            description = "O que tem na tela?",
        ) { openAssistant(OVERLAY_ACTION_DESCRIBE_SCREEN) }
        addPillButton(
            pill,
            root,
            layoutParams,
            icon = android.R.drawable.ic_menu_agenda,
            description = "Resumir o que está na tela",
        ) { openAssistant(OVERLAY_ACTION_SUMMARIZE_SCREEN) }
        addPillButton(
            pill,
            root,
            layoutParams,
            icon = android.R.drawable.ic_menu_preferences,
            description = "Configurações do Atlas",
        ) { openSettings() }
        addPillButton(
            pill,
            root,
            layoutParams,
            icon = android.R.drawable.ic_menu_close_clear_cancel,
            description = "Desativar o Atlas flutuante",
            isLast = true,
        ) { disableOverlay() }

        windowManager?.addView(root, layoutParams)
        params = layoutParams
        overlayView = root
    }

    /** One circular button in the pill. [colorful] gets the brand gradient (only the leading
     *  "Pergunte ao Atlas" button); everything else is a plain dark circle. Returns the [ImageView]
     *  so the caller can keep a reference (only [atlasIconView] needs one, to recolor it per
     *  [AtlasActivity]). */
    private fun addPillButton(
        container: LinearLayout,
        root: View,
        layoutParams: WindowManager.LayoutParams,
        icon: Int?,
        description: String,
        colorful: Boolean = false,
        isLast: Boolean = false,
        onClick: () -> Unit,
    ): ImageView {
        val density = resources.displayMetrics.density
        val size = (44 * density).toInt()
        val button = ImageView(this).apply {
            icon?.let(::setImageResource)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding((11 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (colorful) {
                    gradientType = GradientDrawable.LINEAR_GRADIENT
                    orientation = GradientDrawable.Orientation.TL_BR
                    setColors(ATLAS_BRAND_COLORS)
                } else {
                    setColor(0xFF1C2630.toInt())
                }
            }
            if (!colorful) setColorFilter(0xFFE7EEF5.toInt())
            contentDescription = description
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        container.addView(
            button,
            LinearLayout.LayoutParams(size, size).apply { if (!isLast) marginEnd = (8 * density).toInt() },
        )
        installDragHandler(button, root, layoutParams)
        return button
    }

    /** Recolors the leading "Pergunte ao Atlas" button's gradient to match [activity] — the only
     *  state feedback the pill gives now that there's no status text beside it. */
    private fun updateAtlasIconColors(activity: AtlasActivity) {
        val icon = atlasIconView ?: return
        val colors = when (activity) {
            AtlasActivity.LISTENING -> intArrayOf(0xFF16E4E0.toInt(), 0xFF00AFFF.toInt())
            AtlasActivity.THINKING -> intArrayOf(0xFF5BAEFF.toInt(), 0xFF8E54E9.toInt())
            AtlasActivity.EXECUTING -> intArrayOf(0xFFFFC247.toInt(), 0xFFFF8A00.toInt())
            AtlasActivity.SPEAKING -> intArrayOf(0xFF00D9FF.toInt(), 0xFF5BAEFF.toInt())
            AtlasActivity.ERROR -> intArrayOf(0xFFFF5267.toInt(), 0xFFFF2D55.toInt())
            AtlasActivity.IDLE -> ATLAS_BRAND_COLORS
        }
        (icon.background as? GradientDrawable)?.setColors(colors)
        icon.contentDescription = "Pergunte ao Atlas. ${activityLabel(activity)}"
    }

    private fun installDragHandler(handle: View, overlay: View, layoutParams: WindowManager.LayoutParams) {
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = layoutParams.x
                    startY = layoutParams.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        layoutParams.x = (startX - dx).toInt().coerceIn(
                            0,
                            (resources.displayMetrics.widthPixels - overlay.width).coerceAtLeast(0),
                        )
                        layoutParams.y = (startY + dy).toInt().coerceIn(
                            0,
                            (resources.displayMetrics.heightPixels - overlay.height).coerceAtLeast(0),
                        )
                        windowManager?.updateViewLayout(overlay, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        handle.performClick()
                    } else {
                        persistOverlayPosition(layoutParams.x, layoutParams.y)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun showEvent(text: String) {
        val label = eventText ?: return
        label.text = text
        label.visibility = View.VISIBLE
        hideEvent?.let(mainHandler::removeCallbacks)
        hideEvent = Runnable { label.visibility = View.GONE }.also {
            mainHandler.postDelayed(it, EVENT_DISPLAY_MILLIS)
        }
    }

    private fun setActivity(activity: AtlasActivity) {
        activityState = activity
        updateAtlasIconColors(activity)
    }

    private fun activityLabel(activity: AtlasActivity): String = when (activity) {
        AtlasActivity.IDLE -> "Toque para perguntar"
        AtlasActivity.LISTENING -> "Ouvindo"
        AtlasActivity.THINKING -> "Pensando"
        AtlasActivity.EXECUTING -> "Executando"
        AtlasActivity.SPEAKING -> "Falando"
        AtlasActivity.ERROR -> "Aconteceu um erro"
    }

    private fun disableOverlay() {
        serviceScope.launch {
            try {
                settingsRepository.setOverlayEnabled(false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e(TAG, "Could not save the Atlas overlay preference while disabling it.", failure)
                android.widget.Toast.makeText(
                    this@AtlasOverlayService,
                    "O overlay será encerrado, mas a preferência não pôde ser salva.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
            stopSelf()
        }
    }

    private fun openAssistant(action: String) {
        hiddenForAssistant = true
        applyOverlayVisibility()
        val intent = Intent().setClassName(packageName, ASSISTANT_ACTIVITY).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_OVERLAY_ACTION, action)
        }
        try {
            startActivity(intent)
        } catch (failure: Exception) {
            hiddenForAssistant = false
            applyOverlayVisibility()
            Log.e(TAG, "Could not open the quick Assistant.", failure)
        }
    }

    private fun openSettings() {
        val intent = Intent().setClassName(packageName, SETTINGS_ACTIVITY).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            startActivity(intent)
        } catch (failure: Exception) {
            Log.e(TAG, "Could not open Atlas settings from the overlay menu.", failure)
        }
    }

    private fun applyOverlayVisibility() {
        val hidden = hiddenForAssistant || hiddenForCapture
        overlayView?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        // The window itself still intercepts touches in its (now-invisible) footprint unless told
        // otherwise — most noticeable during a screen capture, where that corner should behave as
        // if the overlay weren't there at all, not swallow a tap meant for whatever is underneath.
        val view = overlayView
        val layout = params
        if (view != null && layout != null) {
            val newFlags = if (hidden) {
                layout.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                layout.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            if (newFlags != layout.flags) {
                layout.flags = newFlags
                try {
                    windowManager?.updateViewLayout(view, layout)
                } catch (failure: Exception) {
                    Log.w(TAG, "Could not update the Atlas overlay touchability.", failure)
                }
            }
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, AtlasOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Atlas flutuante ativo")
            .setContentText("Toque no personagem para abrir o menu do Atlas.")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .addAction(0, "Desativar", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Atlas flutuante", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun eventForAction(action: String): String? = when (action) {
        ACTION_AUTOMATION_EVENT -> "O Atlas terminou uma automação"
        ACTION_PROACTIVE_EVENT -> "O Atlas tem uma sugestão para você"
        else -> null
    }

    companion object {
        private const val TAG = "AtlasOverlay"
        private const val CHANNEL_ID = "atlas_overlay"
        private const val NOTIFICATION_ID = 7314
        private const val EVENT_DISPLAY_MILLIS = 6_000L

        /** The leading pill button's brand gradient (pink → purple → cyan), restored whenever
         *  [AtlasActivity] goes back to IDLE. */
        private val ATLAS_BRAND_COLORS = intArrayOf(0xFFFF5F6D.toInt(), 0xFF8E54E9.toInt(), 0xFF00D9FF.toInt())

        /** Safety net for [hiddenForAssistant]: if the Assistant screen dies without restoring the
         *  overlay (e.g. the task is swiped away mid-conversation) and sends no further heartbeat
         *  (ACTION_ACTIVITY) within this window, the bubble reappears on its own. Generous on
         *  purpose — a normal long voice/thinking turn must never trigger it. */
        private const val ASSISTANT_HIDE_WATCHDOG_MILLIS = 90_000L

        /** Safety net for [hiddenForCapture]: the capture flow (permission dialog + grab + OCR) is
         *  normally done in a few seconds; if no CAPTURE_SHOW arrives within this window (e.g. the
         *  Activity died before its ResultReceiver fired), the bubble reappears on its own. */
        private const val CAPTURE_HIDE_WATCHDOG_MILLIS = 30_000L
        private const val ASSISTANT_ACTIVITY = "com.unyx.atlas.AssistantActivity"
        private const val SETTINGS_ACTIVITY = "com.unyx.atlas.SettingsActivity"
        private const val ACTION_START = "com.unyx.atlas.overlay.START"
        private const val ACTION_STOP = "com.unyx.atlas.overlay.STOP"
        private const val ACTION_AUTOMATION_EVENT = "com.unyx.atlas.overlay.AUTOMATION"
        private const val ACTION_PROACTIVE_EVENT = "com.unyx.atlas.overlay.PROACTIVE"
        private const val ACTION_ACTIVITY = "com.unyx.atlas.overlay.ACTIVITY"
        private const val ACTION_ASSISTANT_HIDE = "com.unyx.atlas.overlay.ASSISTANT_HIDE"
        private const val ACTION_ASSISTANT_SHOW = "com.unyx.atlas.overlay.ASSISTANT_SHOW"
        private const val ACTION_CAPTURE_HIDE = "com.unyx.atlas.overlay.CAPTURE_HIDE"
        private const val ACTION_CAPTURE_SHOW = "com.unyx.atlas.overlay.CAPTURE_SHOW"
        private const val EXTRA_ACTIVITY = "activity"
        const val EXTRA_OVERLAY_ACTION = "com.unyx.atlas.overlay.EXTRA_ACTION"
        const val OVERLAY_ACTION_DESCRIBE_SCREEN = "describe_screen"
        const val OVERLAY_ACTION_QUICK_ASSISTANT = "quick_assistant"
        const val OVERLAY_ACTION_SUMMARIZE_SCREEN = "summarize_screen"
        const val OVERLAY_ACTION_SETTINGS = "settings"
        const val OVERLAY_ACTION_DISABLE = "disable"

        fun start(context: Context): Boolean = startService(context, ACTION_START)

        fun showAutomationEvent(context: Context): Boolean = startService(context, ACTION_AUTOMATION_EVENT)

        fun showProactiveEvent(context: Context): Boolean = startService(context, ACTION_PROACTIVE_EVENT)

        fun updateActivity(context: Context, activity: AtlasActivity) {
            val intent = Intent(context, AtlasOverlayService::class.java)
                .setAction(ACTION_ACTIVITY)
                .putExtra(EXTRA_ACTIVITY, activity.name)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (failure: Exception) {
                Log.w(TAG, "Android did not allow updating the Atlas overlay state.", failure)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AtlasOverlayService::class.java))
        }

        fun hideForAssistant(context: Context) = sendVisibilityAction(context, ACTION_ASSISTANT_HIDE)

        fun restoreAfterAssistant(context: Context) = sendVisibilityAction(context, ACTION_ASSISTANT_SHOW)

        fun hideForScreenCapture(context: Context) = sendVisibilityAction(context, ACTION_CAPTURE_HIDE)

        fun restoreAfterScreenCapture(context: Context) = sendVisibilityAction(context, ACTION_CAPTURE_SHOW)

        private fun sendVisibilityAction(context: Context, action: String) {
            try {
                context.startService(Intent(context, AtlasOverlayService::class.java).setAction(action))
            } catch (failure: Exception) {
                Log.w(TAG, "Could not temporarily change overlay visibility.", failure)
            }
        }

        private fun startService(context: Context, action: String): Boolean {
            if (!Settings.canDrawOverlays(context)) return false
            val intent = Intent(context, AtlasOverlayService::class.java).setAction(action)
            try {
                ContextCompat.startForegroundService(context, intent)
                return true
            } catch (failure: Exception) {
                Log.w(TAG, "Android did not allow starting the opted-in overlay service.", failure)
                return false
            }
        }
    }
}
