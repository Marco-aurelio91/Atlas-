package com.unyx.atlas.feature.notes

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.unyx.atlas.memory.MemoryNote
import com.unyx.atlas.ui.voice.VoiceController

@Composable
fun NoteEditorScreen(viewModel: NotesViewModel, note: MemoryNote, onBack: () -> Unit) {
    val context = LocalContext.current
    var showAiSheet by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    var newTagText by remember { mutableStateOf("") }
    var newItemText by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    var voicePartial by remember { mutableStateOf("") }

    val voiceController = remember { VoiceController(context) }
    DisposableEffect(Unit) { onDispose { voiceController.destroy() } }

    // A borderless field with no cursor visible yet reads as static text, not something to tap —
    // confirmed live (the "onde eu escreto a nota?" confusion): nothing on screen signaled these
    // were editable until this was added. Matches Keep's own behavior: a brand-new note opens
    // straight into the title with the keyboard already up. Only for a truly new note (id == null)
    // — reopening an existing one shouldn't steal focus/pop the keyboard uninvited.
    val titleFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        if (note.id == null) {
            titleFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    fun startDictation() {
        isListening = true
        voiceController.startListening(
            onPartial = { voicePartial = it },
            onFinal = { text ->
                viewModel.updateDraft { d -> d.copy(conteudo = listOfNotNull(d.conteudo?.ifBlank { null }, text).joinToString(" ")) }
            },
            onAmplitude = {},
            onError = { message, _ -> voicePartial = message },
            onEnd = { isListening = false; voicePartial = "" },
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startDictation()
    }

    val pickReminder = rememberDateTimePicker { iso -> viewModel.updateDraft { it.copy(lembreteEm = iso) } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Picking a color previously had zero visible effect until leaving the screen (only
            // the grid card showed it) — confirmed live, confusing enough to look like the picker
            // silently did nothing. Tinting the whole editor matches the card's own full-bleed tint.
            .background(noteColorSwatch(note.cor))
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        EditorTopBar(
            note = note,
            onBack = onBack,
            onTogglePin = viewModel::togglePinDraft,
            onArchive = viewModel::archiveDraft,
            onTrash = viewModel::trashDraft,
        )
        if (viewModel.isSaving) {
            Text(
                text = "Salvando…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        viewModel.saveError?.let { message ->
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
                // Retrying without first finishing the account-key setup in Settings will just fail
                // again with the same error — send the user there instead of a dead-end "Tentar".
                if (viewModel.saveNeedsSecuritySetup) {
                    TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent().setClassName(context, "com.unyx.atlas.SettingsActivity"),
                                )
                            }
                        },
                    ) { Text("Abrir Configurações") }
                } else {
                    TextButton(onClick = viewModel::retrySaveDraft) { Text("Tentar") }
                }
            }
        }
        viewModel.reminderWarning?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        // Two nested columns, not one: only the inner one scrolls and only it carries the
        // weight(1f) that absorbs leftover space, so a short note's slack ends up as scrollable
        // room below the text — not as a gap between the tags row and the toolbar, which read as
        // a stray, disconnected control floating in empty space (reported live, "+tag no meio da
        // tela"). Tags/reminder sit outside the scrollable area so they stay pinned directly above
        // the toolbar, matching the original mockup's own layout.
        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            MinimalTextField(
                value = note.titulo.orEmpty(),
                onValueChange = { titulo -> viewModel.updateDraft { it.copy(titulo = titulo) } },
                placeholder = "Título",
                textStyle = MaterialTheme.typography.titleLarge,
                singleLine = true,
                focusRequester = titleFocusRequester,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(4.dp))

            if (note.tipo == "checklist") {
                note.checklist.forEachIndexed { index, item ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(checked = item.concluido, onCheckedChange = { viewModel.toggleChecklistItem(index) })
                        Text(
                            text = item.texto,
                            style = MaterialTheme.typography.bodyLarge,
                            textDecoration = if (item.concluido) TextDecoration.LineThrough else TextDecoration.None,
                            color = if (item.concluido) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { viewModel.removeChecklistItem(index) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remover item", modifier = Modifier.padding(0.dp))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    MinimalTextField(
                        value = newItemText,
                        onValueChange = { newItemText = it },
                        placeholder = "+ Adicionar item",
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        if (newItemText.isNotBlank()) {
                            viewModel.addChecklistItem(newItemText)
                            newItemText = ""
                        }
                    }) { Icon(Icons.Default.Add, contentDescription = "Adicionar item") }
                }
            } else {
                MinimalTextField(
                    value = note.conteudo.orEmpty(),
                    onValueChange = { conteudo -> viewModel.updateDraft { it.copy(conteudo = conteudo) } },
                    placeholder = "Escreva uma nota...",
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (isListening || voicePartial.isNotBlank()) {
                Text(
                    text = if (voicePartial.isBlank()) "🎤 Ouvindo..." else voicePartial,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }

            TagsRow(
                tags = note.tags,
                newTagText = newTagText,
                onNewTagChange = { newTagText = it },
                onAddTag = { if (newTagText.isNotBlank()) { viewModel.addTag(newTagText); newTagText = "" } },
                onRemoveTag = viewModel::removeTag,
            )

            if (!note.lembreteEm.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 6.dp))
                    Text(formatReminder(note.lembreteEm.orEmpty()), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).clickable(onClick = pickReminder))
                    IconButton(onClick = { viewModel.updateDraft { it.copy(lembreteEm = null) } }) {
                        Icon(Icons.Default.Close, contentDescription = "Remover lembrete")
                    }
                }
            }
        }

        EditorBottomBar(
            onPickColor = { showColorPicker = true },
            onMic = {
                when {
                    isListening -> { voiceController.stopListening(); isListening = false }
                    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startDictation()
                    else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            isListening = isListening,
            onPickReminder = pickReminder,
            onOpenAi = { showAiSheet = true },
        )
    }

    if (showColorPicker) {
        ColorPickerDialog(current = note.cor, onPick = { viewModel.setDraftColor(it); showColorPicker = false }, onDismiss = { showColorPicker = false })
    }

    if (showAiSheet) {
        NoteAiSheet(viewModel = viewModel, onDismiss = { showAiSheet = false })
    }
}


/** A borderless text field with no Material chrome (no fill, no min-height reservation, no label
 * animation space) — the default M3 [TextField] reserves enough padding/min-height on its own to
 * make a Keep-style editor feel like it has huge empty gaps between fields even with its colors
 * turned transparent (confirmed live: title and body read as mostly dead space). [placeholder]
 * renders as plain [Text] underneath the field, shown only while [value] is empty. */
@Composable
private fun MinimalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    singleLine: Boolean = false,
    minLines: Int = 1,
    focusRequester: FocusRequester? = null,
) {
    Box(modifier = modifier) {
        if (value.isEmpty()) {
            Text(placeholder, style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            singleLine = singleLine,
            minLines = minLines,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().let { if (focusRequester != null) it.focusRequester(focusRequester) else it },
        )
    }
}

@Composable
private fun EditorTopBar(note: MemoryNote, onBack: () -> Unit, onTogglePin: () -> Unit, onArchive: () -> Unit, onTrash: () -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Voltar") }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onTogglePin) {
            Icon(
                if (note.fixada) Icons.Default.PushPin else Icons.Outlined.PushPin,
                contentDescription = if (note.fixada) "Desafixar" else "Fixar",
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Mais opções") }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(if (note.arquivada) "Desarquivar" else "Arquivar") },
                    leadingIcon = { Icon(Icons.Default.Unarchive, contentDescription = null) },
                    onClick = { menuExpanded = false; onArchive() },
                )
                DropdownMenuItem(
                    text = { Text("Mover para lixeira") },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = { menuExpanded = false; onTrash() },
                )
            }
        }
    }
}

@Composable
private fun TagsRow(
    tags: List<String>,
    newTagText: String,
    onNewTagChange: (String) -> Unit,
    onAddTag: () -> Unit,
    onRemoveTag: (String) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        tags.forEach { tag ->
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.padding(end = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("🏷 $tag", style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = { onRemoveTag(tag) }, modifier = Modifier.height(18.dp).padding(start = 2.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Remover tag $tag", modifier = Modifier.height(14.dp))
                    }
                }
            }
        }
        MinimalTextField(
            value = newTagText,
            onValueChange = onNewTagChange,
            placeholder = "+ tag",
            singleLine = true,
            // A plain fixed width, not `weight` — this Row scrolls horizontally, and Compose
            // throws if a weighted child sits inside a horizontally-scrolling container. 100.dp
            // was clipping anything longer than a couple characters (see the "espaço pra tag
            // pequeno" report from live testing); 160.dp gives real typing room.
            modifier = Modifier.width(160.dp),
        )
        IconButton(onClick = onAddTag) { Icon(Icons.Default.Add, contentDescription = "Adicionar tag") }
    }
}

@Composable
private fun EditorBottomBar(
    onPickColor: () -> Unit,
    onMic: () -> Unit,
    isListening: Boolean,
    onPickReminder: () -> Unit,
    onOpenAi: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPickReminder) { Icon(Icons.Default.NotificationsActive, contentDescription = "Lembrete") }
        IconButton(onClick = onPickColor) { Icon(Icons.Default.Palette, contentDescription = "Cor") }
        IconButton(onClick = onMic) {
            Icon(if (isListening) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = "Ditar por voz", tint = if (isListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
        IconButton(onClick = onOpenAi) { Icon(Icons.Default.AutoAwesome, contentDescription = "IA Atlas", tint = MaterialTheme.colorScheme.tertiary) }
    }
}

@Composable
private fun ColorPickerDialog(current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cor da nota") },
        text = {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                noteColorOptions().forEach { option ->
                    Box(modifier = Modifier.padding(6.dp)) {
                        Surface(
                            color = noteColorSwatch(option.key),
                            shape = CircleShape,
                            border = if (option.key == current) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier.size(36.dp).clickable { onPick(option.key) },
                        ) {}
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}
