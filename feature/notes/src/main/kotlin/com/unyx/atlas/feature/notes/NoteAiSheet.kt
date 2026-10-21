package com.unyx.atlas.feature.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The ✨ menu — feature:notes' differentiator from a plain Keep clone. Each action is a one-shot
 * Claude call (see [NotesViewModel.runAi]); nothing is written to the note/tasks until the user
 * confirms the preview shown here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteAiSheet(viewModel: NotesViewModel, onDismiss: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            val result = viewModel.aiResult
            when {
                viewModel.aiLoading -> Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }

                result != null -> AiResultView(result = result, viewModel = viewModel, onDone = onDismiss)

                asking -> AskNoteView(
                    question = question,
                    error = viewModel.aiError,
                    onQuestionChange = { question = it },
                    onAsk = { viewModel.aiAsk(question) },
                    onCancel = { asking = false },
                )

                else -> {
                    if (!viewModel.claudeConfigured) {
                        Text(
                            "Atlas ainda não está configurado para usar IA neste app.",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp),
                        )
                    } else {
                        viewModel.aiError?.let { err ->
                            Text(err, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                        AiMenuItem("Resumir", Icons.Default.Summarize, viewModel::aiSummarize)
                        AiMenuItem("Organizar", Icons.Default.Checklist, viewModel::aiOrganize)
                        AiMenuItem("Corrigir texto", Icons.Default.EditNote, viewModel::aiFixText)
                        AiMenuItem("Extrair tarefas", Icons.Default.TaskAlt, viewModel::aiExtractTasks)
                        AiMenuItem("Criar lembrete", Icons.Default.NotificationsActive, viewModel::aiCreateReminder)
                        AiMenuItem("Perguntar sobre esta nota", Icons.Default.QuestionAnswer) { asking = true }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AiMenuItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    androidx.compose.material3.Surface(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(label) },
            leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary) },
        )
    }
}

@Composable
private fun AskNoteView(
    question: String,
    error: String?,
    onQuestionChange: (String) -> Unit,
    onAsk: () -> Unit,
    onCancel: () -> Unit,
) {
    Column {
        Text("Perguntar sobre esta nota", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        TextField(value = question, onValueChange = onQuestionChange, placeholder = { Text("Sua pergunta...") }, modifier = Modifier.fillMaxWidth())
        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel) { Text("Cancelar") }
            Button(onClick = onAsk, enabled = question.isNotBlank()) { Text("Perguntar") }
        }
    }
}

@Composable
private fun AiResultView(result: AiResult, viewModel: NotesViewModel, onDone: () -> Unit) {
    when (result) {
        is AiResult.Text -> Column {
            Text(
                when (result.action) {
                    AiAction.RESUMIR -> "Resumo"
                    AiAction.CORRIGIR -> "Texto corrigido"
                    else -> "Resultado"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(result.text, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { viewModel.dismissAiResult() }) { Text("Cancelar") }
                OutlinedButton(onClick = { viewModel.confirmAiText(result, replace = false); onDone() }) { Text("Adicionar ao final") }
                Button(onClick = { viewModel.confirmAiText(result, replace = true); onDone() }) { Text("Substituir") }
            }
        }

        is AiResult.Tasks -> Column {
            Text("Tarefas", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            if (result.tasks.isEmpty()) {
                Text("Não encontrei nenhuma tarefa clara nessa nota.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                result.tasks.forEach { task ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Text("☐ ", style = MaterialTheme.typography.bodyMedium)
                        Column {
                            Text(task.titulo, style = MaterialTheme.typography.bodyMedium)
                            if (task.prazo != null || task.hora != null) {
                                Text(
                                    "🔔 " + listOfNotNull(task.prazo, task.hora).joinToString(" • "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { viewModel.dismissAiResult() }) { Text("Cancelar") }
                Button(
                    onClick = { viewModel.confirmExtractedTasks(result.tasks); onDone() },
                    enabled = result.tasks.isNotEmpty(),
                ) { Text("Criar tarefas") }
            }
        }

        is AiResult.Reminder -> Column {
            Text("Criar lembrete", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("🔔 ${formatReminder(result.whenIso)}", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { viewModel.dismissAiResult() }) { Text("Cancelar") }
                Button(onClick = { viewModel.confirmReminder(result); onDone() }) { Text("Definir lembrete") }
            }
        }

        is AiResult.Answer -> Column {
            Text(result.question, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(result.text, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { viewModel.dismissAiResult() }) { Text("Fechar") }
            }
        }
    }
}
