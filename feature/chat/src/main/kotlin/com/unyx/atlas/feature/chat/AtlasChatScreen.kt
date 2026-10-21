package com.unyx.atlas.feature.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unyx.atlas.memory.auth.AuthState
import kotlinx.coroutines.launch

/** Material's own "compact" width breakpoint. Below it (a phone) memory still fully replaces chat
 * like a single-pane screen always has; at or above it (a tablet, an unfolded foldable, a phone
 * turned landscape) there's room to show both at once instead of forcing a back-and-forth toggle.
 * Hand-rolled off [androidx.compose.ui.platform.LocalConfiguration] rather than pulling in
 * material3-window-size-class for one boolean — matches this codebase's general dependency-light
 * style (see SupabaseRestClient, MarkdownText, etc.). */
private const val WIDE_SCREEN_BREAKPOINT_DP = 600

private const val MEMORY_PANEL_WIDTH_DP = 360

/** Which non-chat screen is currently showing — Memory and Context are mutually exclusive with
 * each other (and, on a phone, with chat itself); only one makes sense to show at a time. */
private enum class SidePanel { NONE, MEMORY, CONTEXT, AUTOMATIONS, DEBUG }

/**
 * Root of the Atlas chat feature — shows [LoginScreen] until [AuthState.LoggedIn], then
 * [ChatScreen] (with its [ConversationDrawerContent] reachable via the header's menu icon).
 * [pendingAuthCallbackFragment] is the URL fragment from an `atlas://auth-callback` deep link (see
 * AndroidManifest); the hosting Activity owns extracting it from the Intent and clearing it via
 * [onAuthCallbackConsumed] once handled, since a Composable has no Intent of its own.
 */
@Composable
fun AtlasChatScreen(
    pendingAuthCallbackFragment: String?,
    onAuthCallbackConsumed: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenNotes: () -> Unit = {},
    onOpenAtlasConnect: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    assistantVoiceRequested: Boolean = false,
    onAssistantVoiceConsumed: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val isLoggedIn = authState is AuthState.LoggedIn
    var sidePanel by remember { mutableStateOf(SidePanel.NONE) }
    var showChatEntryMenu by remember { mutableStateOf(false) }
    val isWideScreen = LocalConfiguration.current.screenWidthDp >= WIDE_SCREEN_BREAKPOINT_DP
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    LifecycleResumeEffect(Unit) {
        viewModel.setVoiceScreenActive(true)
        onPauseOrDispose { viewModel.setVoiceScreenActive(false) }
    }
    LifecycleResumeEffect(isLoggedIn) {
        if (isLoggedIn) {
            viewModel.onChatResumed()
        }
        onPauseOrDispose { viewModel.onChatPaused() }
    }
    LaunchedEffect(
        isLoggedIn,
        viewModel.isSending,
        viewModel.isListening,
        viewModel.isSpeaking,
        viewModel.executingTool,
        viewModel.chatError,
    ) {
        if (isLoggedIn) {
            val activity = when {
                viewModel.chatError != null -> AtlasActivity.ERROR
                viewModel.isListening -> AtlasActivity.LISTENING
                viewModel.isSpeaking -> AtlasActivity.SPEAKING
                viewModel.executingTool != null -> AtlasActivity.EXECUTING
                viewModel.isSending -> AtlasActivity.THINKING
                else -> AtlasActivity.IDLE
            }
            viewModel.publishOverlayActivity(activity)
        }
    }
    val assistantMicPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && authState is AuthState.LoggedIn && !viewModel.isListening && !viewModel.isSending) {
            viewModel.stopSpeakingNow()
            viewModel.startVoiceInput()
        }
    }
    LifecycleResumeEffect(assistantVoiceRequested, authState) {
        if (assistantVoiceRequested && authState is AuthState.LoggedIn) {
            onAssistantVoiceConsumed()
            sidePanel = SidePanel.NONE
            coroutineScope.launch { drawerState.close() }
            if (!viewModel.isListening && !viewModel.isSending) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    viewModel.stopSpeakingNow()
                    viewModel.startVoiceInput()
                } else {
                    assistantMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
        onPauseOrDispose { }
    }

    LaunchedEffect(pendingAuthCallbackFragment) {
        if (pendingAuthCallbackFragment != null) {
            viewModel.handleAuthCallback(pendingAuthCallbackFragment)
            onAuthCallbackConsumed()
        }
    }

    // The launcher theme leaves its root transparent by design (widgets/overlays need the real
    // wallpaper behind them) — a standalone screen like this one needs its own opaque surface, or
    // the live wallpaper bleeds through and the text becomes unreadable.
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val state = authState) {
            is AuthState.LoggedIn -> {
                val chat: @Composable (Modifier) -> Unit = { chatModifier ->
                    ChatScreen(
                        messages = viewModel.messages,
                        inputText = viewModel.inputText,
                        onInputChange = { viewModel.inputText = it },
                        onSend = viewModel::sendMessage,
                        onRetry = viewModel::retryLastMessage,
                        isSending = viewModel.isSending,
                        executingTool = viewModel.executingTool,
                        streamingText = viewModel.streamingText,
                        userFirstName = viewModel.userFirstName,
                        proactiveNudges = viewModel.proactiveNudges,
                        onAcceptNudge = viewModel::recordNudgeAccepted,
                        onDismissNudge = viewModel::dismissNudge,
                        error = viewModel.chatError,
                        aiGatewayConfigured = viewModel.aiGatewayConfigured,
                        onSignOut = viewModel::signOut,
                        onOpenMemory = { sidePanel = SidePanel.MEMORY },
                        onOpenConversations = { showChatEntryMenu = true },
                        onOpenContext = { sidePanel = SidePanel.CONTEXT },
                        onOpenAutomations = {
                            viewModel.refreshAutomations()
                            sidePanel = SidePanel.AUTOMATIONS
                        },
                        onOpenNotes = onOpenNotes,
                        onOpenSettings = onOpenSettings,
                        onOpenDebug = { sidePanel = SidePanel.DEBUG },
                        pendingImageBase64 = viewModel.pendingImageBase64,
                        onAttachImage = viewModel::attachImage,
                        onClearPendingImage = viewModel::clearPendingImage,
                        pendingPdfFileName = viewModel.pendingPdfFileName,
                        onAttachPdf = viewModel::attachPdf,
                        onClearPendingPdf = viewModel::clearPendingPdf,
                        isListening = viewModel.isListening,
                        voiceInputPhase = viewModel.voiceInputPhase,
                        voicePartialText = viewModel.voicePartialText,
                        voiceAmplitude = viewModel.voiceAmplitude,
                        isSpeaking = viewModel.isSpeaking,
                        speakingMessageText = viewModel.speakingMessageText,
                        voiceError = viewModel.voiceError,
                        onStartVoice = viewModel::startVoiceInput,
                        onCancelVoice = viewModel::cancelVoiceInput,
                        onStopSpeaking = viewModel::stopSpeakingNow,
                        onSpeakMessage = viewModel::speakMessage,
                        speakRepliesEnabled = viewModel.speakRepliesEnabled,
                        handsFreeModeEnabled = viewModel.handsFreeModeEnabled,
                        speechRateValue = viewModel.speechRateValue,
                        onSetSpeakReplies = viewModel::setSpeakReplies,
                        onSetHandsFreeMode = viewModel::setHandsFreeMode,
                        onSetSpeechRate = viewModel::setSpeechRate,
                        confirmTranscriptEnabled = viewModel.confirmTranscriptEnabled,
                        onSetConfirmTranscript = viewModel::setConfirmTranscript,
                        endpointSilenceMillis = viewModel.endpointSilenceMillis,
                        onSetEndpointSilence = viewModel::setEndpointSilence,
                        preferOfflineRecognition = viewModel.preferOfflineRecognition,
                        onSetPreferOfflineRecognition = viewModel::updateOfflineRecognitionPreference,
                        speechVoiceName = viewModel.speechVoiceName,
                        speechVoiceOptions = viewModel.speechVoiceOptions,
                        onDeviceRecognitionAvailable = viewModel.isOnDeviceRecognitionAvailable(),
                        headsetConnected = viewModel.isHeadsetConnected(),
                        onSetSpeechVoice = viewModel::setSpeechVoice,
                        onPreviewSpeechVoice = viewModel::previewSpeechVoice,
                        onLoadSpeechVoices = viewModel::loadSpeechVoices,
                        voiceReviewPending = viewModel.voiceReviewPending,
                        isVoicePaused = viewModel.isVoicePaused,
                        onPauseVoiceInput = viewModel::pauseVoiceInput,
                        onResumeVoiceInput = viewModel::resumeVoiceInput,
                        onFinishVoiceInput = viewModel::finishVoiceInput,
                        onDiscardVoiceDraft = viewModel::discardVoiceDraft,
                        isWideScreen = isWideScreen,
                        modifier = chatModifier,
                    )
                }
                val sidePanelContent: @Composable () -> Unit = {
                    when (sidePanel) {
                        SidePanel.MEMORY -> MemoryScreen(
                            onBack = {
                                sidePanel = SidePanel.NONE
                                viewModel.refreshMemoryContext()
                            },
                        )
                        SidePanel.CONTEXT -> ContextScreen(onBack = { sidePanel = SidePanel.NONE })
                        SidePanel.AUTOMATIONS -> AutomationManagerScreen(
                            automations = viewModel.automations,
                            onBack = { sidePanel = SidePanel.NONE },
                            onRefresh = viewModel::refreshAutomations,
                            onSetEnabled = viewModel::setAutomationEnabled,
                            onSave = viewModel::updateAutomation,
                            onDelete = viewModel::deleteAutomation,
                            onCreate = viewModel::createAutomation,
                        )
                        SidePanel.DEBUG -> DebugScreen(onBack = { sidePanel = SidePanel.NONE })
                        SidePanel.NONE -> Unit
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        drawerContent = {
                            ModalDrawerSheet {
                                ConversationDrawerContent(
                                    conversations = viewModel.conversations,
                                    currentConversationId = viewModel.currentConversationId,
                                    onSelect = { id ->
                                        viewModel.switchConversation(id)
                                        coroutineScope.launch { drawerState.close() }
                                    },
                                    onNewConversation = {
                                        viewModel.newConversation()
                                        coroutineScope.launch { drawerState.close() }
                                    },
                                    onRename = viewModel::renameConversation,
                                    onDelete = viewModel::deleteConversation,
                                    onTogglePin = viewModel::togglePin,
                                    onExport = { id -> shareConversationText(context, viewModel.exportConversationText(id)) },
                                )
                            }
                        },
                    ) {
                        if (isWideScreen) {
                            // Side by side once there's room: the side panel (memory or context) as a
                            // fixed-width strip, chat filling the rest — a tablet/unfolded phone
                            // shouldn't have to choose one or the other the way a phone-width screen
                            // does.
                            Row(modifier = Modifier.fillMaxSize()) {
                                if (sidePanel != SidePanel.NONE) {
                                    Surface(
                                        modifier = Modifier.width(MEMORY_PANEL_WIDTH_DP.dp).fillMaxHeight(),
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    ) { sidePanelContent() }
                                }
                                Box(modifier = Modifier.weight(1f)) { chat(Modifier.fillMaxSize()) }
                            }
                        } else if (sidePanel != SidePanel.NONE) {
                            sidePanelContent()
                        } else {
                            chat(Modifier.fillMaxSize())
                        }
                    }
                }
                if (showChatEntryMenu) {
                    ChatEntryMenuSheet(
                        onDismiss = { showChatEntryMenu = false },
                        onNewAtlasConversation = {
                            showChatEntryMenu = false
                            viewModel.newConversation()
                        },
                        onOpenHistory = {
                            showChatEntryMenu = false
                            coroutineScope.launch { drawerState.open() }
                        },
                        onOpenAtlasConnect = {
                            showChatEntryMenu = false
                            onOpenAtlasConnect()
                        },
                    )
                }
            }
            else -> LoginScreen(
                email = viewModel.email,
                password = viewModel.password,
                onEmailChange = { viewModel.email = it },
                onPasswordChange = { viewModel.password = it },
                error = viewModel.authError,
                busy = viewModel.authBusy,
                pendingConfirmationFor = (state as? AuthState.ConfirmationPending)?.email,
                mfaRequired = state is AuthState.MfaRequired,
                mfaCode = viewModel.mfaCode,
                onMfaCodeChange = viewModel::updateMfaCode,
                onVerifyMfa = viewModel::verifyMfaCode,
                onCancelMfa = viewModel::cancelMfaSignIn,
                onSignIn = viewModel::signIn,
                onSignUp = viewModel::signUp,
            )
        }
    }
}

/** What the header's menu icon opens now, instead of jumping straight to the conversation
 *  history drawer: a small hub with three distinct destinations, so "falar com o Atlas de novo",
 *  "ver o que já conversei com o Atlas" and "ir para o Atlas Connect" (chat humano-a-humano,
 *  independente, sua própria Activity) aren't all buried inside one drawer that used to mix new-
 *  conversation and history together. [onOpenHistory] opens exactly the same drawer/content as
 *  before — nothing about the history UI itself changed, only how you get to it. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ChatEntryMenuSheet(
    onDismiss: () -> Unit,
    onNewAtlasConversation: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAtlasConnect: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ListItem(
            headlineContent = { Text("Conversa Atlas") },
            leadingContent = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().clickable(onClick = onNewAtlasConversation),
        )
        ListItem(
            headlineContent = { Text("Histórico") },
            leadingContent = { Icon(Icons.Default.History, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenHistory),
        )
        ListItem(
            headlineContent = { Text("Atlas Connect") },
            leadingContent = { Icon(Icons.Default.People, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenAtlasConnect),
        )
    }
}
