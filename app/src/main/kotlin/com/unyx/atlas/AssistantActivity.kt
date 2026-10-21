package com.unyx.atlas

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.focus.FocusRequester
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.unyx.atlas.designsystem.theme.LauncherTheme
import com.unyx.atlas.feature.chat.assistant.AssistantScreen
import com.unyx.atlas.feature.chat.assistant.AssistantViewModel
import com.unyx.atlas.feature.chat.AtlasOverlayService
import com.unyx.atlas.feature.chat.capture.AtlasScreenCaptureService
import com.unyx.atlas.data.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.runtime.getValue

/**
 * The quick Assistant screen the OS assist gesture opens (see the ASSIST/VOICE_ASSIST intent-filter
 * on this Activity in the manifest — moved here from [AtlasChatActivity], which keeps only its own
 * MAIN/LAUNCHER icon and the auth deep link). A separate Activity/ViewModel/Screen on purpose: this
 * is meant to be a fast, single-exchange popup, not the full chat with its history/memory/debug
 * panels — see the 2026-09-30 plan. "Abrir chat completo" is the explicit escalation the user asked
 * for; nothing here silently becomes the full experience.
 *
 * Also the target of the system share sheet (ACTION_SEND, text/plain and any image MIME type —
 * see the manifest) for the same reason: sharing a link or photo TO Atlas is exactly this screen's
 * shape, one quick question about one thing, not a reason to drop the user into the full chat's
 * history.
 */
@AndroidEntryPoint
class AssistantActivity : ComponentActivity() {
    @Inject
    lateinit var settingsRepository: SettingsRepository
    private val captureWindowHandler = Handler(Looper.getMainLooper())
    private var captureWindowOriginalAlpha: Float? = null
    private var captureWindowOriginalDimAmount: Float? = null
    private var captureWindowOriginalFlags: Int? = null
    private var overlayHiddenForAssistant = false
    private val captureWindowRestore = Runnable { restoreAssistantWindowAfterCapture() }

    private fun hideAssistantWindowForCapture() {
        val original = window.attributes
        if (captureWindowOriginalAlpha == null) {
            captureWindowOriginalAlpha = original.alpha
            captureWindowOriginalDimAmount = original.dimAmount
            captureWindowOriginalFlags = original.flags
        }
        window.attributes = original.apply {
            alpha = 0f
            dimAmount = 0f
            flags = flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
        }
        captureWindowHandler.removeCallbacks(captureWindowRestore)
        captureWindowHandler.postDelayed(captureWindowRestore, 15_000L)
    }

    private fun restoreAssistantWindowAfterCapture() {
        captureWindowHandler.removeCallbacks(captureWindowRestore)
        val originalAlpha = captureWindowOriginalAlpha ?: return
        captureWindowOriginalAlpha = null
        val originalDimAmount = captureWindowOriginalDimAmount
        val originalFlags = captureWindowOriginalFlags
        captureWindowOriginalDimAmount = null
        captureWindowOriginalFlags = null
        window.attributes = window.attributes.apply {
            alpha = originalAlpha
            if (originalDimAmount != null) dimAmount = originalDimAmount
            if (originalFlags != null) flags = originalFlags
        }
    }

    override fun onDestroy() {
        restoreAssistantWindowAfterCapture()
        if (overlayHiddenForAssistant) AtlasOverlayService.restoreAfterAssistant(this)
        super.onDestroy()
    }

    // This Activity is launchMode="singleTask" (see the manifest) and is the system share-sheet
    // target for text/image/PDF. If an instance is already alive in the background — the user
    // opened the Assistant earlier and just backgrounded it instead of dismissing it — Android
    // routes the next launch (a new share, the assist gesture again, a new overlay action) here
    // instead of a fresh onCreate(). Without this override, that incoming Intent — and whatever was
    // just shared — was silently dropped: setContent()'s one-shot onCreate-time intent reads never
    // ran again. recreate() re-runs onCreate with the new intent (setIntent) while the ViewModelStore
    // (and so the in-progress conversation) survives, matching how a configuration change behaves.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            if (settingsRepository.overlayEnabled.first()) {
                if (AtlasOverlayService.start(this@AssistantActivity)) {
                    AtlasOverlayService.hideForAssistant(this@AssistantActivity)
                    overlayHiddenForAssistant = true
                }
            }
        }
        setContent {
            LauncherTheme {
                val viewModel: AssistantViewModel = hiltViewModel()
                val overlayEnabled by settingsRepository.overlayEnabled.collectAsStateWithLifecycle(initialValue = false)
                val textFieldFocusRequester = remember { FocusRequester() }
                var captureRequestPending by remember { mutableStateOf(false) }
                val overlayPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) {
                    if (Settings.canDrawOverlays(this)) {
                        lifecycleScope.launch {
                            try {
                                settingsRepository.setOverlayEnabled(true)
                                if (!AtlasOverlayService.start(this@AssistantActivity)) {
                                    settingsRepository.setOverlayEnabled(false)
                                    android.widget.Toast.makeText(this@AssistantActivity, "O Android não permitiu iniciar o overlay.", android.widget.Toast.LENGTH_LONG).show()
                                } else {
                                    AtlasOverlayService.hideForAssistant(this@AssistantActivity)
                                    overlayHiddenForAssistant = true
                                }
                            } catch (failure: Exception) {
                                android.widget.Toast.makeText(this@AssistantActivity, "Não foi possível ativar o Atlas flutuante.", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        android.widget.Toast.makeText(this@AssistantActivity, "A permissão não foi concedida; o overlay continua desligado.", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                val screenCapturePermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    val captureIntent = result.data
                    if (result.resultCode == RESULT_OK && captureIntent != null) {
                        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                            override fun onReceiveResult(resultCode: Int, resultData: Bundle) {
                                captureRequestPending = false
                                restoreAssistantWindowAfterCapture()
                                AtlasOverlayService.restoreAfterScreenCapture(this@AssistantActivity)
                                if (resultCode == AtlasScreenCaptureService.RESULT_OK) {
                                    val jpeg = resultData.getByteArray(AtlasScreenCaptureService.EXTRA_JPEG)
                                    if (jpeg != null) {
                                        viewModel.stageScreenCapture(
                                            jpeg,
                                            resultData.getString(AtlasScreenCaptureService.EXTRA_OCR_TEXT).orEmpty(),
                                        )
                                    } else {
                                        viewModel.reportScreenCaptureError("A captura não retornou uma imagem válida.")
                                    }
                                } else {
                                    viewModel.reportScreenCaptureError(
                                        resultData.getString(AtlasScreenCaptureService.EXTRA_ERROR)
                                            ?: "Não foi possível capturar a tela.",
                                    )
                                }
                            }
                        }
                        hideAssistantWindowForCapture()
                        if (!AtlasScreenCaptureService.startCapture(
                                this@AssistantActivity,
                                result.resultCode,
                                captureIntent,
                                receiver,
                            )
                        ) {
                            captureRequestPending = false
                            restoreAssistantWindowAfterCapture()
                            AtlasOverlayService.restoreAfterScreenCapture(this@AssistantActivity)
                            viewModel.reportScreenCaptureError("Não foi possível iniciar a captura autorizada.")
                        }
                    } else {
                        captureRequestPending = false
                        AtlasOverlayService.restoreAfterScreenCapture(this@AssistantActivity)
                    }
                }
                val launchScreenCapture = {
                    if (!captureRequestPending && viewModel.pendingScreenCaptureBase64 == null) {
                        captureRequestPending = true
                        if (overlayEnabled) AtlasOverlayService.hideForScreenCapture(this@AssistantActivity)
                        try {
                            val manager = getSystemService(MediaProjectionManager::class.java)
                            screenCapturePermission.launch(manager.createScreenCaptureIntent())
                        } catch (failure: Exception) {
                            captureRequestPending = false
                            AtlasOverlayService.restoreAfterScreenCapture(this@AssistantActivity)
                            viewModel.reportScreenCaptureError("O Android não conseguiu iniciar a autorização de captura.")
                        }
                    }
                }
                val recordAudioPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) {
                        viewModel.startVoiceInput()
                    } else {
                        // No voice, no typing started either — the user would otherwise land on a
                        // screen with neither input path active. Open the keyboard for them.
                        textFieldFocusRequester.requestFocus()
                    }
                }
                val hasMicPermission = remember {
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                }
                val isShare = remember { intent?.action == Intent.ACTION_SEND }
                val overlayAction = remember { intent?.getStringExtra(AtlasOverlayService.EXTRA_OVERLAY_ACTION) }

                // A shared-in link/photo already IS the input — auto-starting the mic on top of
                // that would be a jarring surprise the assist-gesture voice trigger doesn't have.
                // Pre-fill instead: shared text becomes the question outright (focus the field so
                // the user can edit/add to it before sending); a shared image is staged exactly
                // like a picked attachment, left for the user to ask something about it.
                LaunchedEffect(overlayAction) {
                    when (overlayAction) {
                        AtlasOverlayService.OVERLAY_ACTION_DESCRIBE_SCREEN -> {
                            viewModel.inputText = "Descreva o que está visível nesta tela."
                            launchScreenCapture()
                        }
                        AtlasOverlayService.OVERLAY_ACTION_SUMMARIZE_SCREEN -> {
                            viewModel.inputText = "Resuma o conteúdo visível nesta tela."
                            launchScreenCapture()
                        }
                        AtlasOverlayService.OVERLAY_ACTION_QUICK_ASSISTANT -> {
                            textFieldFocusRequester.requestFocus()
                        }
                        else -> {
                            if (isShare) {
                                val sharedIntent = intent
                                when {
                                    sharedIntent?.type?.startsWith("image/") == true -> {
                                        IntentCompat.getParcelableExtra(sharedIntent, Intent.EXTRA_STREAM, Uri::class.java)
                                            ?.let { viewModel.attachImage(it) }
                                    }
                                    sharedIntent?.type == "application/pdf" -> {
                                        IntentCompat.getParcelableExtra(sharedIntent, Intent.EXTRA_STREAM, Uri::class.java)
                                            ?.let { viewModel.attachPdf(it) }
                                    }
                                    else -> sharedIntent?.getStringExtra(Intent.EXTRA_TEXT)?.let { viewModel.inputText = it }
                                }
                                textFieldFocusRequester.requestFocus()
                            } else if (hasMicPermission) {
                                // Invoked by voice (the assist gesture is usually a voice trigger) — start
                                // listening immediately instead of making the user tap the mic a second time.
                                viewModel.startVoiceInput()
                            } else {
                                recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    }
                }
                LaunchedEffect(
                    viewModel.isListening,
                    viewModel.isSending,
                    viewModel.executingTool,
                    viewModel.errorMessage,
                    viewModel.voiceError,
                ) {
                    if (settingsRepository.overlayEnabled.first()) {
                        val activity = when {
                            viewModel.errorMessage != null || viewModel.voiceError != null -> com.unyx.atlas.feature.chat.AtlasActivity.ERROR
                            viewModel.isListening -> com.unyx.atlas.feature.chat.AtlasActivity.LISTENING
                            viewModel.executingTool != null -> com.unyx.atlas.feature.chat.AtlasActivity.EXECUTING
                            viewModel.isSending -> com.unyx.atlas.feature.chat.AtlasActivity.THINKING
                            else -> com.unyx.atlas.feature.chat.AtlasActivity.IDLE
                        }
                        AtlasOverlayService.updateActivity(this@AssistantActivity, activity)
                    }
                }

                AssistantScreen(
                    inputText = viewModel.inputText,
                    onInputChange = { viewModel.inputText = it },
                    onAsk = viewModel::ask,
                    onStartVoice = {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            viewModel.startVoiceInput()
                        } else {
                            recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onCancelVoice = viewModel::cancelVoiceInput,
                    isListening = viewModel.isListening,
                    voiceAmplitude = viewModel.voiceAmplitude,
                    pendingImageBase64 = viewModel.pendingImageBase64,
                    onAttachImage = viewModel::attachImage,
                    onClearPendingImage = viewModel::clearPendingImage,
                    pendingPdfFileName = viewModel.pendingPdfFileName,
                    onAttachPdf = viewModel::attachPdf,
                    onClearPendingPdf = viewModel::clearPendingPdf,
                    isSending = viewModel.isSending,
                    executingTool = viewModel.executingTool,
                    streamingText = viewModel.streamingText,
                    exchange = viewModel.exchange,
                    errorMessage = viewModel.errorMessage ?: viewModel.voiceError ?: viewModel.screenCaptureError,
                    overlayEnabled = overlayEnabled,
                    pendingScreenCaptureBase64 = viewModel.pendingScreenCaptureBase64,
                    pendingScreenCaptureOcr = viewModel.pendingScreenCaptureOcr,
                    isCapturingScreen = captureRequestPending,
                    onCaptureScreen = launchScreenCapture,
                    onAnalyzeScreenCapture = viewModel::analyzeScreenCapture,
                    onCancelScreenCapture = viewModel::clearScreenCapture,
                    onToggleOverlay = {
                        if (overlayEnabled) {
                            lifecycleScope.launch {
                                try {
                                    settingsRepository.setOverlayEnabled(false)
                                    overlayHiddenForAssistant = false
                                    AtlasOverlayService.stop(this@AssistantActivity)
                                } catch (failure: Exception) {
                                    android.widget.Toast.makeText(this@AssistantActivity, "Não foi possível desativar o Atlas flutuante.", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        } else if (Settings.canDrawOverlays(this)) {
                            lifecycleScope.launch {
                                try {
                                    settingsRepository.setOverlayEnabled(true)
                                    if (!AtlasOverlayService.start(this@AssistantActivity)) {
                                        settingsRepository.setOverlayEnabled(false)
                                        android.widget.Toast.makeText(this@AssistantActivity, "O Android não permitiu iniciar o overlay.", android.widget.Toast.LENGTH_LONG).show()
                                    } else {
                                        AtlasOverlayService.hideForAssistant(this@AssistantActivity)
                                        overlayHiddenForAssistant = true
                                    }
                                } catch (failure: Exception) {
                                    settingsRepository.setOverlayEnabled(false)
                                    overlayHiddenForAssistant = false
                                    android.widget.Toast.makeText(this@AssistantActivity, "Não foi possível ativar o Atlas flutuante.", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        } else {
                            overlayPermission.launch(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName"),
                                ),
                            )
                        }
                    },
                    onOpenFullChat = {
                        viewModel.openFullChat(this)
                        finish()
                    },
                    onDismiss = { finish() },
                    textFieldFocusRequester = textFieldFocusRequester,
                )
            }
        }
    }

}
