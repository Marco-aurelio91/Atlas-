package com.unyx.atlas.feature.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.unyx.atlas.designsystem.theme.LauncherTheme
import com.unyx.atlas.feature.connect.components.ConversationRow
import com.unyx.atlas.feature.connect.model.ConversationRowModel

/**
 * "Conexões" — Atlas Connect's home screen, hosted by its own Activity (same pattern as
 * `NotesActivity`/`NotesScreen`, not a panel inside the Atlas assistant's chat). Reached from the
 * chat's "+ menu" modal (not the old lateral drawer, which stays scoped to the AI assistant's
 * own conversation history).
 *
 * Backed by real local data now ([ConnectionsViewModel] reading `ChatContactStore`/
 * `ChatMessageHistoryStore`) — refreshes on resume so a background fetch loop's new messages
 * show up without the user having to manually pull-to-refresh. Messaging UI itself
 * ([onOpenConversation]) is the caller's job to wire to a real conversation screen.
 */
@Composable
fun ConnectionsScreen(
    onBack: () -> Unit,
    onAddConnection: () -> Unit,
    onOpenConversation: (String) -> Unit = {},
    onOpenProfile: () -> Unit = {},
    viewModel: ConnectionsViewModel = hiltViewModel(),
) {
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    ConnectionsScreenContent(
        connections = viewModel.connections,
        onBack = onBack,
        onAddConnection = onAddConnection,
        onOpenConversation = onOpenConversation,
        onOpenProfile = onOpenProfile,
        onToggleFavorite = viewModel::toggleFavorite,
        onDelete = viewModel::deleteConnection,
    )
}

@Composable
private fun ConnectionsScreenContent(
    connections: List<ConversationRowModel>,
    onBack: () -> Unit,
    onAddConnection: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenProfile: () -> Unit = {},
    onToggleFavorite: (String) -> Unit = {},
    onDelete: (String) -> Unit = {},
) {
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            Row(
                // See AddConnectionScreen's own comment on why this is needed on every screen in
                // this edge-to-edge Activity — without it this header (and its back arrow) sits
                // under the status bar, visible but untappable where it overlaps.
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                }
                Text(
                    text = "Conexões",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                IconButton(onClick = onOpenProfile) {
                    Icon(Icons.Default.AccountCircle, contentDescription = "Meu perfil")
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAddConnection) {
                Icon(Icons.Default.PersonAdd, contentDescription = null)
                Text(text = "Adicionar conexão", modifier = Modifier.padding(start = 8.dp))
            }
        },
    ) { padding ->
        if (connections.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier.padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Nenhuma conexão ainda",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Toque em \"Adicionar conexão\" para parear com outra conta Atlas por QR Code.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(connections, key = { it.conversationId }) { connection ->
                    ConversationRow(
                        model = connection,
                        onClick = onOpenConversation,
                        onToggleFavorite = onToggleFavorite,
                        onDelete = { pendingDelete = it },
                    )
                }
            }
        }
    }

    pendingDelete?.let { conversationId ->
        val name = connections.firstOrNull { it.conversationId == conversationId }?.displayName ?: "esta conexão"
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Excluir conexão?") },
            text = { Text("Você vai remover $name do seu aparelho. O histórico e o pareamento desta conversa serão apagados.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(conversationId)
                    pendingDelete = null
                }) { Text("Excluir", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancelar") } },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectionsScreenEmptyPreview() {
    LauncherTheme {
        ConnectionsScreenContent(connections = emptyList(), onBack = {}, onAddConnection = {}, onOpenConversation = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectionsScreenWithDataPreview() {
    LauncherTheme {
        ConnectionsScreenContent(
            connections = listOf(
                ConversationRowModel("conv-1", "Alice", null, true, "14:32", "Conversa privada", 0),
                ConversationRowModel("conv-2", "Bob", null, false, "Ontem", "Nenhuma mensagem ainda", 0),
            ),
            onBack = {},
            onAddConnection = {},
            onOpenConversation = {},
        )
    }
}
