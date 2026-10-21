package com.unyx.atlas.feature.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unyx.atlas.memory.MemoryNote

@Composable
fun NotesListScreen(viewModel: NotesViewModel, onBack: () -> Unit) {
    var showTrash by remember { mutableStateOf(false) }
    val pickReminderDateTime = rememberDateTimePicker { iso ->
        viewModel.openEditor(null)
        viewModel.updateDraft { it.copy(lembreteEm = iso) }
    }

    Scaffold(
        floatingActionButton = { NoteFab(viewModel = viewModel, onPickReminder = pickReminderDateTime) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            NotesTopBar(onBack = onBack, onOpenTrash = { showTrash = true })
            viewModel.reminderWarning?.let { warning ->
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            viewModel.actionError?.let { message ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::dismissActionError) { Text("OK") }
                }
            }
            // A failed refresh/search with notes already on screen must not blank the list out —
            // this banner is the only thing that changes; ErrorNotesState (full-screen) is reserved
            // for when there's genuinely nothing cached to fall back to.
            if (viewModel.error != null && viewModel.notes.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Não consegui atualizar: ${viewModel.error}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::refresh) { Text("Tentar de novo") }
                }
            }
            OutlinedTextField(
                value = viewModel.searchQuery,
                onValueChange = viewModel::updateSearchQuery,
                placeholder = { Text("Pesquisar nas suas notas...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            FilterChipsRow(filter = viewModel.filter, onSelect = viewModel::selectFilter)

            when {
                // Checked first: the initial load and every search/filter switch go through
                // isLoading with an empty list for at least one frame, which used to fall through
                // to the grid branch below and render a blank screen with no feedback at all.
                viewModel.isLoading && viewModel.notes.isEmpty() -> LoadingNotesState()
                // Checked before the plain empty state — an error (session expired, offline)
                // must never render as an indistinguishable "Nenhuma nota ainda", which would
                // silently hide a real fetch failure behind a normal-looking empty screen.
                viewModel.error != null && viewModel.notes.isEmpty() ->
                    ErrorNotesState(message = viewModel.error!!, onRetry = viewModel::refresh)
                viewModel.notes.isEmpty() -> EmptyNotesState()
                else -> Column(modifier = Modifier.fillMaxSize()) {
                    // A background refresh/search with previous results still on screen — the grid
                    // stays as-is, this is just a cue that new data is on the way.
                    if (viewModel.isLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        verticalItemSpacing = 8.dp,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(viewModel.notes, key = { it.id ?: it.hashCode().toString() }) { note ->
                            NoteCard(note = note, viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }

    if (showTrash) {
        TrashSheet(viewModel = viewModel, onDismiss = { showTrash = false })
    }
}

@Composable
private fun NotesTopBar(onBack: () -> Unit, onOpenTrash: () -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Voltar")
        }
        Text(
            text = "Notas",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Mais opções")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("🗑 Lixeira") },
                    onClick = { menuExpanded = false; onOpenTrash() },
                )
            }
        }
    }
}

@Composable
private fun FilterChipsRow(filter: NoteFilter, onSelect: (NoteFilter) -> Unit) {
    val options = listOf(
        NoteFilter.TODAS to "Todas",
        NoteFilter.FIXADAS to "Fixadas",
        NoteFilter.LISTAS to "Listas",
        NoteFilter.LEMBRETES to "Lembretes",
        NoteFilter.ARQUIVADAS to "Arquivadas",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(selected = filter == value, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

@Composable
private fun EmptyNotesState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Nenhuma nota ainda", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Toque no + para criar uma nota, lista ou lembrete.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoadingNotesState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorNotesState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Não consegui carregar suas notas", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(12.dp))
        androidx.compose.material3.TextButton(onClick = onRetry) { Text("Tentar de novo") }
    }
}

@Composable
private fun NoteFab(viewModel: NotesViewModel, onPickReminder: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ExtendedFloatingActionButton(onClick = { expanded = true }, icon = { Icon(Icons.Default.Add, contentDescription = null) }, text = { Text("Nova") })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Nota") },
                leadingIcon = { Icon(Icons.Default.NoteAdd, contentDescription = null) },
                onClick = { expanded = false; viewModel.openEditor(null, tipo = "nota") },
            )
            DropdownMenuItem(
                text = { Text("Checklist") },
                leadingIcon = { Icon(Icons.Default.Checklist, contentDescription = null) },
                onClick = { expanded = false; viewModel.openEditor(null, tipo = "checklist") },
            )
            DropdownMenuItem(
                text = { Text("Lembrete") },
                leadingIcon = { Icon(Icons.Default.NotificationsActive, contentDescription = null) },
                onClick = { expanded = false; onPickReminder() },
            )
        }
    }
}

@Composable
private fun NoteCard(note: MemoryNote, viewModel: NotesViewModel) {
    Surface(
        color = noteColorSwatch(note.cor),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable { viewModel.openEditor(note) },
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (!note.titulo.isNullOrBlank()) {
                Text(
                    text = (if (note.fixada) "📌 " else "") + note.titulo,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(6.dp))
            } else if (note.fixada) {
                Text("📌", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (note.tipo == "checklist" || note.checklist.isNotEmpty()) {
                note.checklist.take(5).forEach { item ->
                    Text(
                        text = (if (item.concluido) "☑ " else "☐ ") + item.texto,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (item.concluido) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (note.checklist.size > 5) {
                    Text("+${note.checklist.size - 5} itens", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (!note.conteudo.isNullOrBlank()) {
                Text(
                    text = note.conteudo.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (note.tags.isNotEmpty() || !note.lembreteEm.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    note.tags.take(2).forEach { tag ->
                        Text("🏷 $tag", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!note.lembreteEm.isNullOrBlank()) {
                        Text("🔔", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun TrashSheet(viewModel: NotesViewModel, onDismiss: () -> Unit) {
    var trashed by remember { mutableStateOf<List<MemoryNote>>(emptyList()) }
    LaunchedEffect(Unit) { viewModel.loadTrash { trashed = it } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lixeira") },
        text = {
            Column {
                if (trashed.isEmpty()) {
                    Text("Nenhuma nota na lixeira.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                trashed.forEach { note ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = note.titulo?.ifBlank { null } ?: note.conteudo?.take(30) ?: "(sem título)",
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = {
                            // Only drops the row from this sheet's own local list once the restore
                            // actually succeeded — on failure it stays, viewModel.actionError (shown
                            // on the grid underneath) explains why, and the icon is still tappable.
                            viewModel.restore(note) { success ->
                                if (success) trashed = trashed.filterNot { it.id == note.id }
                            }
                        }) {
                            Icon(Icons.Default.Restore, contentDescription = "Restaurar")
                        }
                        IconButton(onClick = {
                            viewModel.deleteForever(note) { success ->
                                if (success) trashed = trashed.filterNot { it.id == note.id }
                            }
                        }) {
                            Icon(Icons.Default.DeleteForever, contentDescription = "Excluir definitivamente")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}
