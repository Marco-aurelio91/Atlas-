package com.unyx.atlas.feature.chat

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.unyx.atlas.data.ai.ChatMessage
import com.unyx.atlas.data.ai.ClaudeAction
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

/** Beyond this, a centered conversation column reads better than bubbles stretched edge-to-edge —
 * matches the design spec's "keep running text near a comfortable width" typography principle
 * applied at the layout level, not just within a single Text. Only applied when [ChatScreen] is
 * given the *full* screen width (see [isWideScreen]) — once the memory side panel is open on a
 * wide screen, chat's own share of the width is already narrower than this and needs no further
 * constraint. */
private val WIDE_SCREEN_CONTENT_MAX_WIDTH = 720.dp

@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: () -> Unit = {},
    isSending: Boolean,
    executingTool: String?,
    streamingText: String?,
    userFirstName: String? = null,
    proactiveNudges: List<ProactiveNudge> = emptyList(),
    onAcceptNudge: (ProactiveNudge) -> Unit = {},
    onDismissNudge: (ProactiveNudge) -> Unit = {},
    error: String?,
    aiGatewayConfigured: Boolean,
    onSignOut: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenConversations: () -> Unit,
    onOpenContext: () -> Unit,
    onOpenAutomations: () -> Unit = {},
    onOpenNotes: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenDebug: () -> Unit = {},
    pendingImageBase64: String?,
    onAttachImage: (android.net.Uri) -> Unit,
    onClearPendingImage: () -> Unit,
    pendingPdfFileName: String? = null,
    onAttachPdf: (android.net.Uri) -> Unit = {},
    onClearPendingPdf: () -> Unit = {},
    isListening: Boolean,
    voiceInputPhase: com.unyx.atlas.ui.voice.VoiceInputPhase,
    voicePartialText: String,
    voiceAmplitude: Float,
    isSpeaking: Boolean,
    speakingMessageText: String? = null,
    voiceError: String?,
    onStartVoice: () -> Unit,
    onCancelVoice: () -> Unit,
    onStopSpeaking: () -> Unit = {},
    onSpeakMessage: (String) -> Unit = {},
    speakRepliesEnabled: Boolean = true,
    handsFreeModeEnabled: Boolean = false,
    speechRateValue: Float = 1.1f,
    onSetSpeakReplies: (Boolean) -> Unit = {},
    onSetHandsFreeMode: (Boolean) -> Unit = {},
    onSetSpeechRate: (Float) -> Unit = {},
    confirmTranscriptEnabled: Boolean = false,
    onSetConfirmTranscript: (Boolean) -> Unit = {},
    endpointSilenceMillis: Int = 1500,
    onSetEndpointSilence: (Int) -> Unit = {},
    preferOfflineRecognition: Boolean = true,
    onSetPreferOfflineRecognition: (Boolean) -> Unit = {},
    speechVoiceName: String = "",
    speechVoiceOptions: List<com.unyx.atlas.ui.voice.VoiceController.SpeechVoice> = emptyList(),
    onDeviceRecognitionAvailable: Boolean = false,
    headsetConnected: Boolean = false,
    onSetSpeechVoice: (String) -> Unit = {},
    onPreviewSpeechVoice: () -> Unit = {},
    onLoadSpeechVoices: () -> Unit = {},
    voiceReviewPending: Boolean = false,
    isVoicePaused: Boolean = false,
    onPauseVoiceInput: () -> Unit = {},
    onResumeVoiceInput: () -> Unit = {},
    onFinishVoiceInput: () -> Unit = {},
    onDiscardVoiceDraft: () -> Unit = {},
    isWideScreen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var showVoiceSettings by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(messages.size, isSending) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(if (isSending) messages.size else messages.lastIndex)
    }
    // Follows along as the reply streams in — a plain (non-animated) scroll so rapid deltas don't
    // stack up a burst of competing scroll animations the way animateScrollToItem would here.
    LaunchedEffect(streamingText) {
        if (isSending && !streamingText.isNullOrEmpty()) listState.scrollToItem(messages.size)
    }
    val activity = when {
        isListening -> AtlasActivity.LISTENING
        isSpeaking -> AtlasActivity.SPEAKING
        !isSending -> AtlasActivity.IDLE
        executingTool != null -> AtlasActivity.EXECUTING
        else -> AtlasActivity.THINKING
    }
    Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (isWideScreen) Modifier.widthIn(max = WIDE_SCREEN_CONTENT_MAX_WIDTH).align(Alignment.CenterHorizontally) else Modifier),
        ) {
            // Same width cap as the message column below it — without this the header spanned the
            // full wide-screen width while the conversation under it was centered/narrower, which
            // read as misaligned on a tablet/unfolded phone.
            ChatHeader(
                onSignOut = onSignOut,
                onOpenMemory = onOpenMemory,
                onOpenConversations = onOpenConversations,
                onOpenContext = onOpenContext,
                onOpenAutomations = onOpenAutomations,
                onOpenNotes = onOpenNotes,
                onOpenSettings = onOpenSettings,
                onOpenDebug = onOpenDebug,
                onOpenVoiceSettings = {
                    onLoadSpeechVoices()
                    showVoiceSettings = true
                },
                activity = activity,
                onStopSpeaking = onStopSpeaking,
                handsFreeModeEnabled = handsFreeModeEnabled,
            )
        }

        if (showVoiceSettings) {
            VoiceSettingsDialog(
                speakReplies = speakRepliesEnabled,
                handsFree = handsFreeModeEnabled,
                speechRate = speechRateValue,
                onSetSpeakReplies = onSetSpeakReplies,
                onSetHandsFree = onSetHandsFreeMode,
                onSetSpeechRate = onSetSpeechRate,
                confirmTranscript = confirmTranscriptEnabled,
                onSetConfirmTranscript = onSetConfirmTranscript,
                endpointSilenceMillis = endpointSilenceMillis,
                onSetEndpointSilence = onSetEndpointSilence,
                preferOfflineRecognition = preferOfflineRecognition,
                onSetPreferOfflineRecognition = onSetPreferOfflineRecognition,
                speechVoiceName = speechVoiceName,
                speechVoiceOptions = speechVoiceOptions,
                onDeviceRecognitionAvailable = onDeviceRecognitionAvailable,
                headsetConnected = headsetConnected,
                onSetSpeechVoice = onSetSpeechVoice,
                onPreviewSpeechVoice = onPreviewSpeechVoice,
                onDismiss = { showVoiceSettings = false },
            )
        }

        if (!aiGatewayConfigured) {
            Text(
                text = "O gateway de IA do Atlas ainda não está configurado para este app.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (isWideScreen) Modifier.widthIn(max = WIDE_SCREEN_CONTENT_MAX_WIDTH).align(Alignment.CenterHorizontally) else Modifier),
        ) {
            if (messages.isEmpty() && !isSending) {
                EmptyState(
                    modifier = Modifier.weight(1f),
                    userFirstName = userFirstName,
                    proactiveNudges = proactiveNudges,
                    onSuggestionClick = { suggestion ->
                        onInputChange(suggestion)
                        onSend()
                    },
                    onAcceptNudge = onAcceptNudge,
                    onDismissNudge = onDismissNudge,
                )
            } else {
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(messages) { index, message ->
                            MessageRow(
                                message,
                                onOpenMemory = onOpenMemory,
                                isSpeakingThis = isSpeaking && speakingMessageText == message.content,
                                onSpeak = { onSpeakMessage(message.content) },
                                showAssistantLabel = index == 0 || messages.getOrNull(index - 1)?.role != "assistant",
                            )
                        }
                        if (isSending) {
                            item {
                                StreamingRow(
                                    streamingText = streamingText,
                                    activity = activity,
                                    executingTool = executingTool,
                                    showAssistantLabel = messages.lastOrNull()?.role != "assistant",
                                )
                            }
                        }
                    }
                    // Only once there's enough to actually scroll away from — a freshly opened short
                    // conversation never needs this. requiresScrollDistance + isScrollInProgress avoid
                    // a one-frame flash of the button during the list's own auto-scroll-to-bottom.
                    val canScrollDown by remember { derivedStateOf { listState.canScrollForward } }
                    // Fully qualified: a ColumnScope.AnimatedVisibility overload is otherwise found
                    // ambiguous here purely because an ancestor Column is somewhere up the implicit
                    // receiver chain, even though this call site itself is inside a Box, not a Column.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = canScrollDown,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        SmallFloatingActionButton(
                            onClick = {
                                coroutineScope.launch {
                                    if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Icon(imageVector = Icons.Default.ArrowDownward, contentDescription = "Ir para o fim")
                        }
                    }
                }
            }

            if (error != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetry) {
                        Text("Tentar novamente")
                    }
                }
            }

            if (voiceError != null) {
                Text(
                    text = voiceError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            PendingAttachmentsRow(
                imageBase64 = pendingImageBase64,
                pdfFileName = pendingPdfFileName,
                onRemoveImage = onClearPendingImage,
                onRemovePdf = onClearPendingPdf,
            )
            if (voiceReviewPending) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Revise a transcrição e toque em enviar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDiscardVoiceDraft) {
                        Icon(Icons.Default.Close, contentDescription = "Descartar transcrição", tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }

            val context = LocalContext.current
            val attachmentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    if (context.contentResolver.getType(uri) == "application/pdf") onAttachPdf(uri) else onAttachImage(uri)
                }
            }
            val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) onStartVoice()
            }

            val isVoiceActive = isListening || isVoicePaused
            Column(modifier = Modifier.fillMaxWidth()) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = isVoiceActive,
                    enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
                    label = "voiceStatusPanel",
                ) {
                    InlineVoicePanel(
                        partialText = voicePartialText,
                        amplitude = voiceAmplitude,
                        onCancel = onCancelVoice,
                        paused = isVoicePaused,
                        onPause = onPauseVoiceInput,
                        onResume = onResumeVoiceInput,
                        onFinish = onFinishVoiceInput,
                        error = voiceError,
                        phase = voiceInputPhase,
                    )
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 1.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { attachmentLauncher.launch(arrayOf("image/*", "application/pdf")) },
                            enabled = !isSending,
                        ) {
                            Icon(imageVector = Icons.Default.AttachFile, contentDescription = "Anexar imagem ou PDF")
                        }
                        TextField(
                            value = inputText,
                            onValueChange = onInputChange,
                            placeholder = { Text("Pergunte ao Atlas…") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (!isSending && (inputText.isNotBlank() || pendingImageBase64 != null || pendingPdfFileName != null)) onSend()
                                },
                            ),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(24.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            ),
                        )
                        val sendReady = inputText.isNotBlank() || pendingImageBase64 != null || pendingPdfFileName != null
                        AnimatedContent(
                            targetState = isVoiceActive to sendReady,
                            transitionSpec = { fadeIn() + scaleIn() togetherWith fadeOut() + scaleOut() },
                            label = "inputActionButton",
                        ) { (voiceActive, isSend) ->
                            when {
                                voiceActive -> IconButton(
                                    onClick = onCancelVoice,
                                    enabled = !isSending,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.errorContainer),
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = "Parar gravação", tint = MaterialTheme.colorScheme.onErrorContainer)
                                }
                                isSend -> IconButton(
                                    onClick = onSend,
                                    enabled = !isSending,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(if (isSending) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary),
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "Enviar",
                                        tint = if (isSending) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                                else -> IconButton(
                                    onClick = {
                                        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                        if (hasPermission) onStartVoice() else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    },
                                    enabled = !isSending,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                ) {
                                    Icon(imageVector = Icons.Default.Mic, contentDescription = "Falar", tint = MaterialTheme.colorScheme.onPrimary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}

/** Painel de voz integrado (inline) para escuta e transcrição diretamente na mesma tela do chat. */
@Composable
private fun InlineVoicePanel(
    partialText: String,
    amplitude: Float,
    onCancel: () -> Unit,
    paused: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    error: String?,
    phase: com.unyx.atlas.ui.voice.VoiceInputPhase,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VoiceWaveform(amplitude = if (paused) 0f else amplitude)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (paused) "Pausado" else when (phase) {
                            com.unyx.atlas.ui.voice.VoiceInputPhase.PREPARING -> "Preparando microfone…"
                            com.unyx.atlas.ui.voice.VoiceInputPhase.TRANSCRIBING -> "Transcrevendo…"
                            com.unyx.atlas.ui.voice.VoiceInputPhase.LISTENING -> "Ouvindo você…"
                            else -> ""
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                val previewText = if (paused && error != null) error else partialText
                if (previewText.isNotBlank()) {
                    Text(
                        text = previewText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (paused && error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Cancelar gravação")
            }
            IconButton(
                onClick = if (paused) onResume else onPause,
                enabled = phase == com.unyx.atlas.ui.voice.VoiceInputPhase.LISTENING || paused,
            ) {
                Icon(
                    imageVector = if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = if (paused) "Continuar gravação" else "Pausar gravação",
                )
            }
            IconButton(
                onClick = onFinish,
                enabled = phase == com.unyx.atlas.ui.voice.VoiceInputPhase.LISTENING || paused,
                modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Enviar transcrição",
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun VoiceWaveform(amplitude: Float) {
    val normalizedAmplitude = amplitude.coerceIn(0f, 10f)
    val barScales = listOf(0.35f, 0.7f, 1f, 0.65f, 0.4f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.height(24.dp),
    ) {
        barScales.forEach { scale ->
            VoiceWaveformBar(amplitude = normalizedAmplitude, scale = scale)
        }
    }
}

@Composable
private fun VoiceWaveformBar(amplitude: Float, scale: Float) {
    val height by animateDpAsState(
        targetValue = (5f + amplitude * 1.25f * scale).dp,
        animationSpec = tween(durationMillis = 120),
        label = "voiceWaveformBar",
    )
    Box(
        modifier = Modifier
            .width(3.dp)
            .height(height)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
    )
}@Composable
private fun PendingAttachmentsRow(
    imageBase64: String?,
    pdfFileName: String?,
    onRemoveImage: () -> Unit,
    onRemovePdf: () -> Unit,
) {
    if (imageBase64 == null && pdfFileName == null) return
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (imageBase64 != null) {
            item {
                val bitmap = rememberDecodedBitmap(imageBase64)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            bitmap?.let {
                                Image(
                                    bitmap = it,
                                    contentDescription = "Imagem anexada",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Imagem anexada",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(onClick = onRemoveImage, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Remover imagem", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        if (pdfFileName != null) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = pdfFileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 140.dp),
                        )
                        IconButton(onClick = onRemovePdf, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Remover documento", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatHeader(
    onSignOut: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenConversations: () -> Unit,
    onOpenContext: () -> Unit,
    onOpenAutomations: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    activity: AtlasActivity,
    onStopSpeaking: () -> Unit = {},
    handsFreeModeEnabled: Boolean = false,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenConversations) {
            Icon(imageVector = Icons.Default.Menu, contentDescription = "Conversas")
        }
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(text = "Atlas", style = MaterialTheme.typography.titleLarge)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // Tap to interrupt — there was previously no way to stop Atlas mid-sentence once
                // it started talking. Only clickable while actually speaking; harmless the rest of
                // the time (no indication it's tappable when it isn't).
                modifier = if (activity == AtlasActivity.SPEAKING) {
                    Modifier.clickable(onClick = onStopSpeaking)
                } else {
                    Modifier
                },
            ) {
                AtlasCoreIndicator(activity = activity, size = 8.dp)
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    // "mãos-livres" is appended here (not a separate row/icon) so it stays a quiet
                    // reminder next to the status word instead of new visual weight in the header —
                    // it matters most right when the mic is about to reopen on its own after Atlas
                    // finishes talking, which is exactly when this row is already being read.
                    text = buildString {
                        append(
                            when (activity) {
                                AtlasActivity.LISTENING -> "ouvindo"
                                AtlasActivity.EXECUTING -> "executando"
                                AtlasActivity.THINKING -> "pensando"
                                AtlasActivity.SPEAKING -> "falando · toque para parar"
                                AtlasActivity.IDLE -> "Pronto para ajudar"
                                AtlasActivity.ERROR -> "erro"
                            },
                        )
                        if (handsFreeModeEnabled) append(" · mãos-livres")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(imageVector = Icons.Default.MoreVert, contentDescription = "Mais opções")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                Text(
                    text = "SEU ATLAS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                )
                // [ATLAS FLUTUANTE — EM CRIAÇÃO] Settings had no entry point from the Atlas app.
                DropdownMenuItem(
                    text = { Text("Configurações") },
                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onOpenSettings()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Memória") },
                    leadingIcon = { Icon(Icons.Default.Lightbulb, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onOpenMemory()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Notas") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.StickyNote2, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onOpenNotes()
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = "DISPOSITIVO",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                )
                DropdownMenuItem(
                    text = { Text("Contexto atual") },
                    leadingIcon = { Icon(Icons.Default.Sensors, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onOpenContext()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Automações") },
                    leadingIcon = { Icon(Icons.Default.Alarm, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onOpenAutomations()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Configurações de voz") },
                    leadingIcon = { Icon(Icons.Default.GraphicEq, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onOpenVoiceSettings()
                    },
                )
                // Debuggable APK only (the `debug` build type, never `release`) — raw table
                // names/similarity scores have no business in a menu a non-technical user (or a
                // future second user of this app) would ever open. See DebugScreen's own doc for
                // why the screen itself doesn't also re-check this: one gate, at the only entry point.
                val isDebugBuild = (LocalContext.current.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                if (isDebugBuild) {
                    DropdownMenuItem(
                        text = { Text("Diagnóstico") },
                        leadingIcon = { Icon(Icons.Default.BugReport, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onOpenDebug()
                        },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                DropdownMenuItem(
                    text = { Text("Sair da conta") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onSignOut()
                    },
                )
            }
        }
    }
}

/** Varies only the greeting word by time of day — a small, honest touch (the device's own clock,
 * nothing inferred) rather than a generic "Olá." at every hour. */
private fun timeOfDayGreeting(): String {
    val hour = java.time.LocalTime.now().hour
    return when (hour) {
        in 5..11 -> "Bom dia"
        in 12..17 -> "Boa tarde"
        else -> "Boa noite"
    }
}

/** [firstName] is null until [ChatViewModel.loadUserFirstName] finds a saved "nome" fact — falls
 * back to the plain greeting (with its own closing period) rather than ever showing a placeholder
 * name. Dropped the period before a name ("Bom dia, Marco") since a name already reads as the
 * sentence's natural end; kept without one ("Bom dia.") so the un-personalized case doesn't read
 * as a cut-off fragment. */
private fun greetingFor(firstName: String?): String =
    if (firstName.isNullOrBlank()) "${timeOfDayGreeting()}." else "${timeOfDayGreeting()}, $firstName"

private data class QuickAction(val label: String, val icon: ImageVector, val prompt: String)

/** Fixed, not time-of-day-varied like the old suggestion chips were — four categories the user can
 * always find in the same place beats a changing set that needs re-reading each time. Each prompt
 * is deliberately something Atlas can already act on for real — "Previsão do tempo" and "Explorar
 * recursos" map straight to tool-backed prompts that already worked as chips; "Meu dia" and "Criar
 * lembrete" are plain conversational turns Atlas can follow up on, not calls to tools that don't
 * exist yet. */
private val QUICK_ACTIONS = listOf(
    QuickAction("Meu dia", Icons.Default.Event, "Como está meu dia hoje?"),
    QuickAction("Criar lembrete", Icons.Default.Notifications, "Quero criar um lembrete"),
    QuickAction("Previsão do tempo", Icons.Default.WbSunny, "Qual é a previsão do tempo?"),
    QuickAction("Explorar recursos", Icons.Default.Apps, "O que você pode fazer?"),
)

@Composable
private fun EmptyState(
    userFirstName: String?,
    proactiveNudges: List<ProactiveNudge>,
    onSuggestionClick: (String) -> Unit,
    onAcceptNudge: (ProactiveNudge) -> Unit = {},
    onDismissNudge: (ProactiveNudge) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // A landscape phone, or several ATTENTION nudges stacked with the quick-action cards,
            // can exceed the space this gets (it's already height-bound by the caller's weight(1f))
            // — scroll rather than silently clip top/bottom. The incoming height stays bounded, so
            // short content is still centered by verticalArrangement below exactly as before.
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyStateAvatar()
        Spacer(modifier = Modifier.height(20.dp))
        Text(text = greetingFor(userFirstName), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            text = "Como posso ajudar você hoje?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
        )
        // The one place Atlas says something before being asked — see ChatViewModel's
        // proactiveNudges doc for what's eligible and why. Never an auto-sent chat message, just
        // these lines on the empty state; tapping one sends a real message rather than the nudge
        // text itself pretending to be user input. PASSIVE is deliberately excluded here — it's
        // computed (a real but weak routine signal) but section 19's own definition is "BACKGROUND
        // → não interromper"; showing it as a card would be exactly the interruption that level is
        // supposed to not be.
        proactiveNudges.filter { it.level != ProactiveLevel.PASSIVE }.forEach { nudge ->
            // CRITICAL (an overdue task) and ATTENTION (a deadline due today) both read as urgent —
            // the architecture spec's "níveis" (section 15/19), made visible here as color rather
            // than as a routing decision to some other surface (no Island integration yet). SUGGEST
            // (a routine guess) stays the calmer, non-urgent treatment.
            val isUrgent = nudge.level == ProactiveLevel.ATTENTION || nudge.level == ProactiveLevel.CRITICAL
            val containerColor = if (isUrgent) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            }
            val onContainerColor = if (isUrgent) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(containerColor)
                    .clickable {
                        onAcceptNudge(nudge)
                        onSuggestionClick(nudge.prompt)
                    }
                    .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.NotificationsActive,
                    contentDescription = null,
                    tint = onContainerColor,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = nudge.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = onContainerColor,
                    modifier = Modifier.weight(1f),
                )
                // Dismiss — the only source of *rejection* signal (see ChatViewModel's
                // recordNudgeFeedback): without this, "the user didn't tap it" would get
                // conflated with "the user said no", which isn't the same thing at all.
                IconButton(onClick = { onDismissNudge(nudge) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dispensar sugestão",
                        tint = onContainerColor,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        if (proactiveNudges.isNotEmpty()) Spacer(modifier = Modifier.height(4.dp))
        QuickActionsGrid(
            onActionClick = onSuggestionClick,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** The large circle-in-a-halo above the greeting — a soft, pale ring behind [AtlasCoreIndicator]'s
 * usual solid dot, at empty-state scale. Both circles use theme colors (not fixed RGB) so this
 * stays correct in dark mode the same way the rest of the screen already does. */
@Composable
private fun EmptyStateAvatar() {
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
        )
        AtlasCoreIndicator(activity = AtlasActivity.IDLE, size = 48.dp)
    }
}

@Composable
private fun QuickActionsGrid(onActionClick: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        QUICK_ACTIONS.chunked(2).forEach { rowActions ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowActions.forEach { action ->
                    QuickActionCard(
                        action = action,
                        onClick = { onActionClick(action.prompt) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickActionCard(action: QuickAction, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = action.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** The in-progress assistant turn while [isSending] — before the first streamed token, this is
 * exactly the old plain "pensando…"/"executando X" status row; once text starts arriving it
 * switches to rendering that text live (same visual treatment as a finished [MessageRow]'s
 * assistant branch), with the status line still shown underneath while a tool is actively running
 * so the user doesn't lose track of what Atlas is doing just because some prose already streamed
 * in ahead of the tool call. */
/** The "Atlas said this" block — core indicator, the "Atlas" label, and the Markdown-rendered
 * text — shared by a finished turn ([MessageRow]) and one still streaming in ([StreamingRow]) so
 * the two only ever diverge where they actually need to (their own trailing content: an
 * executing-tool line for one, action cards/memory caption for the other), not in this shared part. */
@Composable
private fun AssistantReplyBlock(
    activity: AtlasActivity,
    text: String,
    showLabel: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (showLabel) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 3.dp),
            ) {
                AtlasCoreIndicator(activity = activity, size = 7.dp)
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = "Atlas",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        MarkdownText(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun StreamingRow(
    streamingText: String?,
    activity: AtlasActivity,
    executingTool: String?,
    showAssistantLabel: Boolean = true,
) {
    if (streamingText.isNullOrBlank()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AtlasCoreIndicator(activity = activity, modifier = Modifier.padding(end = 8.dp))
            Text(
                text = if (activity == AtlasActivity.EXECUTING && executingTool != null) {
                    executingLabel(executingTool)
                } else {
                    "Atlas está pensando…"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth()) {
            AssistantReplyBlock(activity = activity, text = streamingText, showLabel = showAssistantLabel)
            if (activity == AtlasActivity.EXECUTING && executingTool != null) {
                Text(
                    text = executingLabel(executingTool),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun MessageRow(
    message: ChatMessage,
    onOpenMemory: () -> Unit,
    isSpeakingThis: Boolean = false,
    onSpeak: () -> Unit = {},
    showAssistantLabel: Boolean = true,
) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    if (message.role == "user") {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(horizontalAlignment = Alignment.End) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.82f)
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    message.imageBase64?.let { base64 ->
                        val bitmap = rememberDecodedBitmap(base64)
                        bitmap?.let {
                            Image(
                                bitmap = it,
                                contentDescription = "Imagem enviada",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .padding(bottom = 6.dp),
                            )
                        }
                    }
                    message.pdfFileName?.let { fileName ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                text = fileName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                MessageActionRow(
                    onCopy = {
                        coroutineScope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Mensagem", message.content)))
                        }
                    },
                    onShare = { shareText(context, message.content) },
                )
            }
        }
    } else {
        // A quiet left-aligned surface balances the user's stronger accent bubble without
        // making assistant replies look like a second primary action.
        Column(modifier = Modifier.fillMaxWidth()) {
            AssistantReplyBlock(
                activity = AtlasActivity.IDLE,
                text = message.content,
                showLabel = showAssistantLabel,
            )
            message.actions.forEach { action ->
                val memoryText = memoryPhrase(action.toolName, action.failed)
                val cardSpec = actionCardSpec(action.toolName)
                when {
                    // Memory writes stay a light caption — a card would overstate a soft "I'll
                    // remember that" the way a receipt-style card would for "I opened Chrome".
                    memoryText != null -> Surface(
                        modifier = Modifier.padding(top = 6.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Text(
                            text = memoryText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                    // A real device action gets a proper Action Card — this IS the thing the
                    // design spec's item 9 asked for, distinct from a mere confirmation line.
                    cardSpec != null -> ActionCard(
                        icon = cardSpec.icon,
                        label = cardSpec.label,
                        detail = action.resultSummary,
                        failed = action.failed,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    // Anything else (e.g. get_weather, a pure query) shows nothing here — Claude's
                    // own reply text above already states the answer; a card/line under it would
                    // just be noise ("feito o quê?").
                }
            }
            if (message.usedMemory) {
                var memoryExpanded by remember { mutableStateOf(false) }
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        text = if (memoryExpanded) "🧠 Memória utilizada ▾" else "🧠 Memória utilizada",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable(enabled = message.memoryLabels.isNotEmpty()) { memoryExpanded = !memoryExpanded },
                    )
                    // Only what actually went into this turn's prompt — never raw table/column
                    // names or a numeric confidence score, per the design spec's explicit "no
                    // debug-y memory text" rule (item 32) — a plain sentence, not a stat.
                    if (memoryExpanded) {
                        Column(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(12.dp),
                        ) {
                            message.memoryLabels.forEach { label ->
                                Text(
                                    text = "· $label",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            Text(
                                text = "Ver memória completa",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .clickable(onClick = onOpenMemory),
                            )
                        }
                    }
                }
            }
            MessageActionRow(
                onCopy = {
                    coroutineScope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Mensagem", message.content)))
                    }
                },
                onShare = { shareText(context, message.content) },
                onSpeak = onSpeak,
                isSpeaking = isSpeakingThis,
            )
        }
    }
}

/** Small icon-button row under a message bubble — copy and share always, "ouvir"/"parar" only for
 * an assistant reply ([onSpeak] null for a user message, see [MessageRow]). Deliberately always
 * visible rather than behind a long-press menu — these are the actions worth a tap on nearly any
 * message, not a rare one needing to be tucked away. */
@Composable
private fun MessageActionRow(onCopy: () -> Unit, onShare: () -> Unit, onSpeak: (() -> Unit)? = null, isSpeaking: Boolean = false) {
    Row(modifier = Modifier.padding(top = 2.dp)) {
        IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Copiar mensagem",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = "Compartilhar mensagem",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onSpeak != null) {
            IconButton(onClick = onSpeak, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (isSpeaking) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (isSpeaking) "Parar" else "Ouvir",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Warm, deliberately generic phrasing for a memory write — never echoes the raw "chave = valor"
 * the model actually saw, since the reply text right above it already puts the specifics into
 * natural words on its own. Null for anything that isn't a memory tool. */
private fun memoryPhrase(toolName: String, failed: Boolean): String? = when (toolName) {
    "save_fact" -> if (failed) "Tentei anotar isso, mas não consegui." else "💭 Vou lembrar disso."
    "save_preference" -> if (failed) "Tentei guardar essa preferência, mas não consegui." else "💭 Anotado."
    "save_person" -> if (failed) "Tentei guardar essa pessoa, mas não consegui." else "💭 Vou lembrar dela."
    "save_decision" -> if (failed) "Tentei guardar essa regra, mas não consegui." else "💭 Combinado."
    "create_project" -> if (failed) "Tentei salvar esse projeto, mas não consegui." else "💭 Vou lembrar desse projeto."
    "create_task" -> if (failed) "Tentei criar essa tarefa, mas não consegui." else "📋 Tarefa criada."
    "complete_task" -> if (failed) "Tentei marcar como concluída, mas não consegui." else "✅ Concluída."
    "update_project" -> if (failed) "Tentei atualizar o projeto, mas não consegui." else "💭 Projeto atualizado."
    "update_working_memory" -> if (failed) "Tentei anotar o andamento, mas não consegui." else "💭 Vou lembrar onde paramos."
    "link_person" -> if (failed) "Tentei anotar essa conexão, mas não consegui." else "💭 Vou lembrar dessa conexão."
    "create_note" -> if (failed) "Tentei anotar isso, mas não consegui." else "📝 Nota criada."
    "update_note" -> if (failed) "Tentei atualizar a nota, mas não consegui." else "📝 Nota atualizada."
    else -> null
}

/** Gerund-style "doing it right now" label for [ThinkingRow] while a tool is mid-execution —
 * distinct from [actionCardSpec]'s past-tense card label ("App aberto") shown once it's done. */
private fun executingLabel(toolName: String): String = when (toolName) {
    "open_app" -> "Abrindo o app…"
    "set_alarm" -> "Criando o alarme…"
    "start_timer" -> "Iniciando o timer…"
    "set_volume" -> "Ajustando o volume…"
    "set_brightness" -> "Ajustando o brilho…"
    "create_reminder" -> "Criando o lembrete…"
    "send_whatsapp_message" -> "Abrindo o WhatsApp…"
    "call_person" -> "Abrindo o discador…"
    "get_weather" -> "Consultando o clima…"
    "save_fact", "save_preference", "save_person", "save_decision", "create_project", "update_project", "update_working_memory", "link_person" -> "Anotando…"
    "create_task" -> "Criando a tarefa…"
    "complete_task" -> "Marcando como concluída…"
    "list_tasks" -> "Consultando tarefas…"
    "find_related_people" -> "Buscando pessoas relacionadas…"
    "create_automation" -> "Criando a automação…"
    "list_automations" -> "Consultando automações…"
    "delete_automation" -> "Removendo a automação…"
    "create_note" -> "Anotando…"
    "search_notes" -> "Buscando nas notas…"
    "update_note" -> "Atualizando a nota…"
    else -> "Executando…"
}

private data class ActionCardSpec(val icon: ImageVector, val label: String)

/** Icon + short label per action tool — the Card's own detail text is [ClaudeAction.resultSummary]
 * verbatim (already a complete, natural Portuguese sentence written by executeTool()), so there's
 * no fragile parsing of that string here. Null for anything that isn't one of these action tools
 * (memory tools go through [memoryPhrase] instead; a pure query like get_weather gets neither). */
private fun actionCardSpec(toolName: String): ActionCardSpec? = when (toolName) {
    "open_app" -> ActionCardSpec(Icons.Default.Apps, "App aberto")
    "set_alarm" -> ActionCardSpec(Icons.Default.Alarm, "Alarme")
    "start_timer" -> ActionCardSpec(Icons.Default.Timer, "Timer")
    "set_volume" -> ActionCardSpec(Icons.AutoMirrored.Filled.VolumeUp, "Volume")
    "set_brightness" -> ActionCardSpec(Icons.Default.BrightnessMedium, "Brilho")
    "create_reminder" -> ActionCardSpec(Icons.Default.Event, "Lembrete")
    "send_whatsapp_message" -> ActionCardSpec(Icons.AutoMirrored.Filled.Chat, "WhatsApp")
    "call_person" -> ActionCardSpec(Icons.Default.Call, "Ligação")
    "create_automation" -> ActionCardSpec(Icons.Default.Alarm, "Automação criada")
    "delete_automation" -> ActionCardSpec(Icons.Default.Alarm, "Automação removida")
    "open_internet_panel" -> ActionCardSpec(Icons.Default.Sensors, "Painel de Internet")
    else -> null
}

@Composable
private fun ActionCard(icon: ImageVector, label: String, detail: String, failed: Boolean, modifier: Modifier = Modifier) {
    val container = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val onContainer = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        modifier = modifier
            .fillMaxWidth(0.9f)
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = onContainer.copy(alpha = 0.12f),
            modifier = Modifier.size(36.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(imageVector = icon, contentDescription = null, tint = onContainer, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(text = label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = onContainer)
            Text(text = detail, style = MaterialTheme.typography.bodySmall, color = onContainer.copy(alpha = 0.9f))
        }
    }
}

/** Three real preferences that didn't exist before: whether voice-originated replies get spoken
 * at all, whether the mic picks back up automatically after Atlas finishes talking (hands-free
 * conversation), and how fast Atlas talks. See ChatViewModel.VoiceSettings' doc for why each one
 * was missing. */
@Composable
private fun VoiceSettingsDialog(
    speakReplies: Boolean,
    handsFree: Boolean,
    speechRate: Float,
    onSetSpeakReplies: (Boolean) -> Unit,
    onSetHandsFree: (Boolean) -> Unit,
    onSetSpeechRate: (Float) -> Unit,
    confirmTranscript: Boolean,
    onSetConfirmTranscript: (Boolean) -> Unit,
    endpointSilenceMillis: Int,
    onSetEndpointSilence: (Int) -> Unit,
    preferOfflineRecognition: Boolean,
    onSetPreferOfflineRecognition: (Boolean) -> Unit,
    speechVoiceName: String,
    speechVoiceOptions: List<com.unyx.atlas.ui.voice.VoiceController.SpeechVoice>,
    onDeviceRecognitionAvailable: Boolean,
    headsetConnected: Boolean,
    onSetSpeechVoice: (String) -> Unit,
    onPreviewSpeechVoice: () -> Unit,
    onDismiss: () -> Unit,
) {
    var voiceMenuExpanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configurações de voz") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Falar respostas", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Quando você fala com o Atlas, a resposta também é falada",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = speakReplies, onCheckedChange = onSetSpeakReplies)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Modo mãos-livres", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Depois de responder, volta a ouvir automaticamente",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = handsFree, onCheckedChange = onSetHandsFree, enabled = speakReplies)
                }
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Text(
                        "Velocidade da fala — ${String.format(java.util.Locale.US, "%.1f", speechRate)}x",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = speechRate,
                        onValueChange = onSetSpeechRate,
                        valueRange = 0.7f..1.6f,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Revisar transcrição", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Confirme ou corrija o texto antes de enviar",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = confirmTranscript, onCheckedChange = onSetConfirmTranscript)
                }
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Text("Pausa para concluir · ${(endpointSilenceMillis / 1000f).let { String.format(java.util.Locale.US, "%.1f", it) }} s")
                    Slider(
                        value = endpointSilenceMillis.toFloat(),
                        onValueChange = { onSetEndpointSilence(it.toInt()) },
                        valueRange = 800f..3000f,
                        steps = 10,
                    )
                    Text(
                        "Aumente se o Atlas costuma encerrar enquanto você ainda está falando.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Priorizar reconhecimento offline", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            when {
                                preferOfflineRecognition && onDeviceRecognitionAvailable -> "Reconhecimento offline disponível; o serviço pode usar rede como alternativa."
                                !preferOfflineRecognition && onDeviceRecognitionAvailable -> "O aparelho oferece reconhecimento offline, mas esta opção está desativada."
                                else -> "Reconhecimento offline não detectado; o serviço de voz pode usar internet."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = preferOfflineRecognition, onCheckedChange = onSetPreferOfflineRecognition)
                }
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Text("Voz em português", style = MaterialTheme.typography.bodyMedium)
                    Box {
                        TextButton(onClick = { voiceMenuExpanded = true }) {
                            Text(speechVoiceOptions.firstOrNull { it.name == speechVoiceName }?.label ?: "Voz padrão do aparelho")
                        }
                        DropdownMenu(expanded = voiceMenuExpanded, onDismissRequest = { voiceMenuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Voz padrão do aparelho") },
                                onClick = { onSetSpeechVoice(""); voiceMenuExpanded = false },
                            )
                            speechVoiceOptions.forEach { voice ->
                                DropdownMenuItem(
                                    text = { Text(if (voice.requiresNetwork) "${voice.label} · requer rede" else voice.label) },
                                    onClick = { onSetSpeechVoice(voice.name); voiceMenuExpanded = false },
                                )
                            }
                        }
                    }
                    if (speechVoiceOptions.isEmpty()) {
                        Text(
                            "Nenhuma voz em português foi encontrada. Instale uma nas configurações de texto para fala do Android.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onPreviewSpeechVoice) { Text("Ouvir prévia") }
                }
                Text(
                    "Dispositivo de áudio detectado: ${if (headsetConnected) "fone conectado" else "alto-falante do aparelho"}. O Android escolhe a rota. Toque no Atlas para parar a fala; interromper falando só é ativado com fone para evitar eco.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}
