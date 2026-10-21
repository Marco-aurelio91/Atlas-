package com.unyx.atlas.feature.connect.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.unyx.atlas.designsystem.theme.LauncherTheme
import com.unyx.atlas.feature.connect.components.MessageBubble
import com.unyx.atlas.feature.connect.model.MessageBubbleModel
import com.unyx.atlas.feature.connect.model.MessageDeliveryState
import com.unyx.atlas.feature.connect.model.MessageDirection
import com.unyx.atlas.ui.component.ContactAvatar

/**
 * T03 from `docs/ATLAS_CHAT_Plano_Arquitetura_Layouts_e_Telas.md` — one open conversation, wired
 * to real data via [ConversationViewModel]. No camera/voice/attachment affordances (out of scope
 * per the plan's own §10: "Sem câmera, áudio ou anexo enquanto fora do escopo real"). An error
 * banner (not a toast) shows a failed send with its draft text restored, matching the plan's
 * "erros indicam ação sem revelar detalhes técnicos desnecessários".
 */
@Composable
fun ConversationScreen(
    conversationId: String,
    onBack: () -> Unit,
    onOpenContactDetail: (String) -> Unit = {},
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    // System back is handled by AtlasConnectScreen's own NavDisplay now — see its doc. This
    // screen only still needs to wire the on-screen back arrow below.
    LaunchedEffect(conversationId) { viewModel.open(conversationId) }

    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Excluir mensagem?") },
            // Plan §12's own wording, verbatim.
            text = { Text("Esta mensagem será removida apenas deste aparelho.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteId = null
                    viewModel.deleteMessage(id)
                }) { Text("Excluir", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteId = null }) { Text("Cancelar") } },
        )
    }

    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    if (showClearHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            title = { Text("Excluir histórico desta conversa?") },
            text = { Text("Todas as mensagens serão removidas deste aparelho. A conexão e o pareamento continuam valendo — vocês podem seguir conversando normalmente depois.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearHistoryConfirm = false
                    viewModel.clearHistory()
                }) { Text("Excluir", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearHistoryConfirm = false }) { Text("Cancelar") } },
        )
    }

    var showMenu by remember { mutableStateOf(false) }
    var showMuteOptions by remember { mutableStateOf(false) }
    if (showMuteOptions) {
        AlertDialog(
            onDismissRequest = { showMuteOptions = false },
            title = { Text("Silenciar notificações") },
            text = {
                Column {
                    listOf(
                        "1 hora" to MuteOption.ONE_HOUR,
                        "8 horas" to MuteOption.EIGHT_HOURS,
                        "Até reativar" to MuteOption.UNTIL_REACTIVATED,
                        "Não silenciar" to MuteOption.UNMUTE,
                    ).forEach { (label, option) ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    showMuteOptions = false
                                    viewModel.setMute(option)
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showMuteOptions = false }) { Text("Cancelar") } },
        )
    }

    val listState = rememberLazyListState()
    LaunchedEffect(viewModel.visibleMessages.size) {
        if (viewModel.visibleMessages.isNotEmpty()) listState.animateScrollToItem(viewModel.visibleMessages.size - 1)
    }

    Scaffold(
        topBar = {
            Row(
                // See AddConnectionScreen's own comment — same edge-to-edge status bar overlap
                // issue, same fix, on every screen in this module.
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                }
                Row(
                    modifier = Modifier.padding(start = 4.dp).weight(1f).clickable { onOpenContactDetail(conversationId) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ContactAvatar(name = viewModel.contactName, photoUri = null, photoBytes = viewModel.contactAvatarBytes, size = 36.dp)
                    Text(
                        text = viewModel.contactName,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Mais opções")
                    }
                    // Plan §13 "Menu da conversa" — "Bloquear" fica na tela de detalhes da
                    // conexão, junto da identidade verificada, não aqui.
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Buscar nesta conversa") },
                            onClick = {
                                showMenu = false
                                viewModel.toggleSearch()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (viewModel.isMuted) "Reativar notificações" else "Silenciar notificações") },
                            onClick = {
                                showMenu = false
                                if (viewModel.isMuted) viewModel.setMute(MuteOption.UNMUTE) else showMuteOptions = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Excluir histórico da conversa") },
                            onClick = {
                                showMenu = false
                                showClearHistoryConfirm = true
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (viewModel.isSearching) {
                    OutlinedTextField(
                        value = viewModel.searchQuery,
                        onValueChange = { viewModel.searchQuery = it },
                        placeholder = { Text("Buscar nesta conversa...") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = viewModel::toggleSearch) {
                                Icon(Icons.Default.Close, contentDescription = "Fechar busca")
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (viewModel.isBlocked) {
                    Text(
                        text = "Você bloqueou esta conexão. Desbloqueie nos detalhes da conexão para enviar e receber mensagens.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), state = listState) {
                    items(viewModel.visibleMessages, key = { it.messageId }) { message ->
                        var showActions by remember { mutableStateOf(false) }
                        Box {
                            MessageBubble(model = message, onLongPress = { showActions = true })
                            // Plan §12: "Pressionar a mensagem abre ações" — exactly these two,
                            // nothing else (no copiar/compartilhar/encaminhar/editar).
                            DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                                DropdownMenuItem(
                                    text = { Text("Responder") },
                                    onClick = {
                                        showActions = false
                                        viewModel.startReplyTo(message)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Excluir do meu histórico") },
                                    onClick = {
                                        showActions = false
                                        pendingDeleteId = message.messageId
                                    },
                                )
                            }
                        }
                    }
                }
                viewModel.errorMessage?.let { error ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = "Tentar novamente",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp).clickable(onClick = viewModel::retryPending),
                        )
                    }
                }
                // Plan §11: "coloca uma referência acima do compositor com nome e trecho curto.
                // Cancelar remove a referência, mantendo o texto digitado."
                viewModel.replyTarget?.let { target ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val authorLabel = if (target.direction == MessageDirection.SENT) "Você" else viewModel.contactName
                            Text(
                                text = "Respondendo a $authorLabel",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = target.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = viewModel::clearReplyTarget) {
                            Icon(Icons.Default.Close, contentDescription = "Cancelar resposta")
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().imePadding().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = viewModel.draft,
                        onValueChange = { viewModel.draft = it },
                        placeholder = { Text("Mensagem...") },
                        modifier = Modifier.weight(1f),
                        maxLines = 5,
                        enabled = !viewModel.isBlocked,
                    )
                    IconButton(
                        onClick = viewModel::send,
                        enabled = !viewModel.isBlocked && !viewModel.isSending && viewModel.draft.isNotBlank(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar")
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConversationBubblesPreview() {
    LauncherTheme {
        Column {
            MessageBubble(MessageBubbleModel("1", "Oi! Chegou?", MessageDirection.SENT, "14:32", MessageDeliveryState.SERVER_ACCEPTED))
            MessageBubble(MessageBubbleModel("2", "Chegou sim!", MessageDirection.RECEIVED, "14:33", null))
        }
    }
}
