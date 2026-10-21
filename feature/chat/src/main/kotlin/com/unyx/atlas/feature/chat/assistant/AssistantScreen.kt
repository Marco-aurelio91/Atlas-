package com.unyx.atlas.feature.chat.assistant

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import com.unyx.atlas.feature.chat.rememberDecodedBitmap
import com.unyx.atlas.feature.chat.shareText

/**
 * The quick Assistant screen — appears when the OS assist gesture fires (see AssistantActivity).
 * Deliberately compact: one question, one answer, a way to escalate to the full chat. Not the same
 * composable as [com.unyx.atlas.feature.chat.ChatScreen] (that one renders a whole scrollable
 * history + drawer + side panels this screen has no use for). The window itself already animates
 * in/out (Theme.AtlasLauncher.Assistant uses Animation.Dialog), so content here only needs to
 * animate *between its own states*, not its own entrance/exit.
 */
@Composable
fun AssistantScreen(
    inputText: String,
    onInputChange: (String) -> Unit,
    onAsk: () -> Unit,
    onStartVoice: () -> Unit,
    onCancelVoice: () -> Unit,
    isListening: Boolean,
    voiceAmplitude: Float,
    pendingImageBase64: String? = null,
    onAttachImage: (Uri) -> Unit = {},
    onClearPendingImage: () -> Unit = {},
    pendingPdfFileName: String? = null,
    onAttachPdf: (Uri) -> Unit = {},
    onClearPendingPdf: () -> Unit = {},
    isSending: Boolean,
    executingTool: String?,
    streamingText: String?,
    exchange: AssistantExchange?,
    errorMessage: String?,
    overlayEnabled: Boolean = false,
    onToggleOverlay: () -> Unit = {},
    pendingScreenCaptureBase64: String? = null,
    pendingScreenCaptureOcr: String? = null,
    isCapturingScreen: Boolean = false,
    onCaptureScreen: () -> Unit = {},
    onAnalyzeScreenCapture: () -> Unit = {},
    onCancelScreenCapture: () -> Unit = {},
    onOpenFullChat: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    textFieldFocusRequester: FocusRequester = remember { FocusRequester() },
) {
    val haptics = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
                .systemBarsPadding()
                .imePadding()
                // Swallow taps so they don't fall through to the scrim's onDismiss behind it.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = false,
                ) {},
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
        ) {
            Column(modifier = Modifier.animateContentSize().padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Atlas",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Fechar",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                TextButton(onClick = onToggleOverlay) {
                    Text(if (overlayEnabled) "Desativar personagem flutuante" else "Usar personagem flutuante")
                }
                TextButton(
                    onClick = onCaptureScreen,
                    enabled = !isSending && !isCapturingScreen && pendingScreenCaptureBase64 == null,
                ) {
                    if (isCapturingScreen) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(
                            Icons.Default.PhotoCamera,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (isCapturingScreen) "Capturando tela…" else "Capturar tela para analisar")
                }

                Spacer(Modifier.heightIn(min = 16.dp))

                AnimatedContent(
                    // Keyed on the kind of thing being shown, not every field, so e.g. streamingText
                    // updating mid-answer doesn't retrigger the enter/exit transition on every token.
                    targetState = when {
                        isSending -> AssistantStatus.SENDING
                        exchange != null -> AssistantStatus.ANSWERED
                        errorMessage != null -> AssistantStatus.ERROR
                        isListening -> AssistantStatus.LISTENING
                        else -> AssistantStatus.IDLE
                    },
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "assistantStatus",
                ) { status ->
                    when (status) {
                        AssistantStatus.ANSWERED -> exchange?.let {
                            val clipboard = LocalClipboardManager.current
                            val context = LocalContext.current
                            Column {
                                Column(modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                                    it.imageBase64?.let { base64 ->
                                        val bitmap = rememberDecodedBitmap(base64)
                                        bitmap?.let { bmp ->
                                            Image(
                                                bitmap = bmp,
                                                contentDescription = "Imagem enviada",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(120.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .padding(bottom = 8.dp),
                                            )
                                        }
                                    }
                                    it.pdfFileName?.let { fileName ->
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.InsertDriveFile,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                fileName,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    QuestionChip(it.question)
                                    Spacer(Modifier.heightIn(min = 12.dp))
                                    Text(it.answer, style = MaterialTheme.typography.bodyLarge)
                                    it.actions.forEach { action ->
                                        Spacer(Modifier.heightIn(min = 8.dp))
                                        ActionResultChip(label = action.resultSummary, failed = action.failed)
                                    }
                                }
                                Row {
                                    IconButton(
                                        onClick = { clipboard.setText(AnnotatedString(it.answer)) },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.ContentCopy,
                                            contentDescription = "Copiar resposta",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(
                                        onClick = { shareText(context, it.answer) },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Share,
                                            contentDescription = "Compartilhar resposta",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Spacer(Modifier.heightIn(min = 4.dp))
                                if (it.isScreenCapture) {
                                    Text(
                                        "Análise temporária: a captura não foi salva no histórico.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TextButton(onClick = onCancelScreenCapture) { Text("Limpar análise") }
                                } else {
                                    TextButton(onClick = onOpenFullChat) { Text("Abrir chat completo") }
                                }
                            }

                            if (pendingScreenCaptureBase64 != null) {
                                val bitmap = rememberDecodedBitmap(pendingScreenCaptureBase64)
                                AlertDialog(
                                    onDismissRequest = onCancelScreenCapture,
                                    title = { Text("Revisar captura de tela") },
                                    text = {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 420.dp)
                                                .verticalScroll(rememberScrollState()),
                                        ) {
                                            bitmap?.let {
                                                Surface(
                                                    shape = RoundedCornerShape(12.dp),
                                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                                ) {
                                                    Image(
                                                        bitmap = it,
                                                        contentDescription = "Prévia da captura de tela",
                                                        contentScale = ContentScale.Fit,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .heightIn(max = 200.dp),
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.height(12.dp))
                                            Text(
                                                "A imagem e o texto OCR abaixo serão enviados ao serviço de IA do Atlas " +
                                                    "(Claude ou, se necessário, Gemini). Revise antes de continuar; evite " +
                                                    "enviar senhas, dados financeiros ou outras informações sensíveis. " +
                                                    "Esta análise não executa ações nem é salva no histórico.",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            Text(
                                                "Texto detectado (OCR)",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                pendingScreenCaptureOcr?.takeIf { it.isNotBlank() }
                                                    ?: "Nenhum texto legível foi reconhecido. A imagem ainda poderá ser analisada.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(onClick = onAnalyzeScreenCapture, enabled = !isSending) {
                                            Text("Enviar para análise")
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = onCancelScreenCapture) { Text("Cancelar") }
                                    },
                                )
                            }
                        }
                        AssistantStatus.SENDING -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                executingTool?.let { toolProgressLabel(it) } ?: streamingText?.takeIf { it.isNotBlank() } ?: "Pensando…",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        AssistantStatus.ERROR -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                errorMessage.orEmpty(),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        AssistantStatus.LISTENING -> Row(verticalAlignment = Alignment.CenterVertically) {
                            ListeningDot()
                            Spacer(Modifier.width(10.dp))
                            Text("Ouvindo…", style = MaterialTheme.typography.bodyLarge)
                        }
                        AssistantStatus.IDLE -> Text(
                            "Como posso ajudar?",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.heightIn(min = 16.dp))

                if (pendingImageBase64 != null) {
                    val bitmap = rememberDecodedBitmap(pendingImageBase64)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            bitmap?.let {
                                Image(bitmap = it, contentDescription = "Imagem anexada", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Imagem anexada",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onClearPendingImage, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Remover imagem", modifier = Modifier.size(16.dp))
                        }
                    }
                }
                if (pendingPdfFileName != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                        Box(
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            pendingPdfFileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onClearPendingPdf, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Remover documento", modifier = Modifier.size(16.dp))
                        }
                    }
                }

                // One unified pill — 56dp tall, 28dp corner radius (= half-height, a true stadium
                // shape), 8dp internal padding — matching the design reference exactly, instead of
                // a bordered text field sitting next to a separate round button.
                Surface(
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // A single picker for both image and PDF — see ChatScreen's identical
                        // choice for why one button covers both instead of crowding this row.
                        val context = LocalContext.current
                        val attachmentLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.OpenDocument(),
                        ) { uri ->
                            if (uri != null) {
                                if (context.contentResolver.getType(uri) == "application/pdf") onAttachPdf(uri) else onAttachImage(uri)
                            }
                        }
                        IconButton(
                            onClick = { attachmentLauncher.launch(arrayOf("image/*", "application/pdf")) },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Default.AttachFile,
                                contentDescription = "Anexar imagem ou PDF",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                            if (inputText.isEmpty()) {
                                Text(
                                    "Fale ou digite com o Atlas…",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            BasicTextField(
                                value = inputText,
                                onValueChange = onInputChange,
                                modifier = Modifier.fillMaxWidth().focusRequester(textFieldFocusRequester),
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                singleLine = true,
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onAsk()
                                }),
                            )
                        }
                        val sendReady = inputText.isNotBlank() || pendingImageBase64 != null || pendingPdfFileName != null
                        // Grows subtly with the user's own voice volume while listening — the same
                        // signal/formula ChatScreen's VoiceOverlay already uses (0..10 dB -> 1.0..1.3x).
                        val micScale = if (isListening) 1f + (voiceAmplitude.coerceIn(0f, 10f) / 10f) * 0.3f else 1f
                        IconButton(
                            onClick = {
                                when {
                                    sendReady -> {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onAsk()
                                    }
                                    isListening -> onCancelVoice()
                                    else -> {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onStartVoice()
                                    }
                                }
                            },
                            modifier = Modifier.size(36.dp).scale(micScale),
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (sendReady || isListening) MaterialTheme.colorScheme.primary else Color.Transparent,
                                contentColor = if (sendReady || isListening) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) {
                            Icon(
                                when {
                                    sendReady -> Icons.AutoMirrored.Filled.Send
                                    isListening -> Icons.Default.Stop
                                    else -> Icons.Default.Mic
                                },
                                contentDescription = when {
                                    sendReady -> "Enviar"
                                    isListening -> "Parar de ouvir"
                                    else -> "Falar"
                                },
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class AssistantStatus { IDLE, LISTENING, SENDING, ANSWERED, ERROR }

/** Present-progressive phrasing for the "executing a tool" moment — distinct from
 * `ChatViewModel`'s `ACTION_TOOL_LABELS` (past tense, built for the full chat's completed-action
 * cards); this one is purely about what's happening *right now*, so it gets its own small map
 * instead of forcing one wording to serve both tenses. */
private fun toolProgressLabel(toolName: String): String = when (toolName) {
    "open_app" -> "Abrindo o aplicativo…"
    "set_alarm" -> "Criando o alarme…"
    "start_timer" -> "Iniciando o timer…"
    "set_volume" -> "Ajustando o volume…"
    "set_brightness" -> "Ajustando o brilho…"
    "send_whatsapp_message" -> "Preparando mensagem no WhatsApp…"
    "create_reminder" -> "Criando o lembrete…"
    "call_person" -> "Abrindo o discador…"
    "get_weather" -> "Consultando o clima…"
    "create_note", "update_note" -> "Salvando a nota…"
    "create_task", "complete_task" -> "Atualizando a tarefa…"
    else -> "Executando…"
}

/** 32×32dp circular brand mark — a placeholder "A" monogram, swappable for a real logo asset
 * later without touching layout (same slot, same size). */
@Composable
private fun QuestionChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActionResultChip(label: String, failed: Boolean) {
    Surface(
        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** A plain static dot (no pulse animation — the mic button itself now carries the voice-reactive
 * animation via [voiceAmplitude], so this just needs to read as "listening", not duplicate it). */
@Composable
private fun ListeningDot() {
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(MaterialTheme.colorScheme.error, CircleShape),
    )
}
