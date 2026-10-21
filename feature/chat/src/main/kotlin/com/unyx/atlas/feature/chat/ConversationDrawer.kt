package com.unyx.atlas.feature.chat

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The conversation list (item 20–22 of the design spec: nova conversa, renomear, pesquisar,
 * exportar, fixar). Pinned conversations first, then most-recently-updated — [conversations] is
 * expected pre-sorted that way (see [ChatViewModel.refreshConversations]); this composable only
 * filters by [searchQuery] and groups pinned vs. not for the section headers.
 */
@Composable
fun ConversationDrawerContent(
    conversations: List<ConversationSummary>,
    currentConversationId: String,
    onSelect: (String) -> Unit,
    onNewConversation: () -> Unit,
    onRename: (id: String, title: String) -> Unit,
    onDelete: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onExport: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<ConversationSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ConversationSummary?>(null) }

    val filtered = remember(conversations, query) {
        if (query.isBlank()) {
            conversations
        } else {
            conversations.filter { (it.title ?: "Nova conversa").contains(query, ignoreCase = true) }
        }
    }
    val pinned = filtered.filter { it.pinned }
    val recent = filtered.filterNot { it.pinned }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Conversas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                FilledTonalButton(onClick = onNewConversation) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Text("Nova", modifier = Modifier.padding(start = 8.dp))
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Pesquisar conversas") },
                leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Limpar busca")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)) {
                if (pinned.isNotEmpty()) {
                    item { DrawerSectionLabel("Fixadas") }
                    items(pinned, key = { it.id }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            selected = conversation.id == currentConversationId,
                            onClick = { onSelect(conversation.id) },
                            onRename = { renameTarget = conversation },
                            onDelete = { deleteTarget = conversation },
                            onTogglePin = { onTogglePin(conversation.id) },
                            onExport = { onExport(conversation.id) },
                        )
                    }
                }
                if (recent.isNotEmpty()) {
                    item { DrawerSectionLabel(if (pinned.isNotEmpty()) "Recentes" else "") }
                    items(recent, key = { it.id }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            selected = conversation.id == currentConversationId,
                            onClick = { onSelect(conversation.id) },
                            onRename = { renameTarget = conversation },
                            onDelete = { deleteTarget = conversation },
                            onTogglePin = { onTogglePin(conversation.id) },
                            onExport = { onExport(conversation.id) },
                        )
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            text = if (query.isBlank()) "Nenhuma conversa ainda" else "Nada encontrado para \"$query\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        RenameDialog(
            initialTitle = target.title ?: "Nova conversa",
            onDismiss = { renameTarget = null },
            onConfirm = { newTitle ->
                onRename(target.id, newTitle)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Apagar conversa?") },
            text = { Text("\"${target.title ?: "Nova conversa"}\" será apagada. Essa ação não pode ser desfeita.") },
            confirmButton = {
                Button(
                    onClick = { onDelete(target.id); deleteTarget = null },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Apagar") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun DrawerSectionLabel(text: String) {
    if (text.isBlank()) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun ConversationRow(
    conversation: ConversationSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onExport: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = conversation.title ?: "Nova conversa",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
            )
            Text(
                text = relativeDate(conversation.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(imageVector = Icons.Default.MoreVert, contentDescription = "Mais opções da conversa")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(if (conversation.pinned) "Desafixar" else "Fixar") },
                    leadingIcon = {
                        Icon(
                            imageVector = if (conversation.pinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                            contentDescription = null,
                        )
                    },
                    onClick = { menuExpanded = false; onTogglePin() },
                )
                DropdownMenuItem(
                    text = { Text("Renomear") },
                    leadingIcon = { Icon(imageVector = Icons.Default.DriveFileRenameOutline, contentDescription = null) },
                    onClick = { menuExpanded = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("Exportar") },
                    leadingIcon = { Icon(imageVector = Icons.Default.IosShare, contentDescription = null) },
                    onClick = { menuExpanded = false; onExport() },
                )
                DropdownMenuItem(
                    text = { Text("Apagar", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(imageVector = Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menuExpanded = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(initialTitle: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Renomear conversa") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            Button(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** "Hoje" / "Ontem" / "dd de MMM" — enough precision for a conversation list, no need for exact
 * timestamps the way a message row might warrant. */
private fun relativeDate(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    val date = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = java.time.LocalDate.now()
    return when {
        date == today -> "Hoje"
        date == today.minusDays(1) -> "Ontem"
        else -> date.format(DateTimeFormatter.ofPattern("d 'de' MMM", Locale("pt", "BR")))
            .replaceFirstChar { it.titlecase(Locale("pt", "BR")) }
    }
}

/** Launches the system share sheet with [text] — the "exportar" action (item 21). A plain-text
 * transcript, not a file: no attachments subsystem exists yet for this to save as, and a share
 * sheet already lets the user route it to Files/Drive/WhatsApp/etc. themselves. */
fun shareConversationText(context: android.content.Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(intent, "Exportar conversa").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
