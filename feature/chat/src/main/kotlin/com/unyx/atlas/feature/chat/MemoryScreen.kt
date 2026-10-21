package com.unyx.atlas.feature.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.unyx.atlas.memory.MemoryDecision
import com.unyx.atlas.memory.MemoryEpisode
import com.unyx.atlas.memory.MemoryFact
import com.unyx.atlas.memory.MemoryPerson
import com.unyx.atlas.memory.MemoryPreference
import com.unyx.atlas.memory.MemoryCorrection
import com.unyx.atlas.memory.MemoryProject
import com.unyx.atlas.memory.MemoryRoutine
import com.unyx.atlas.memory.MemoryTask
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * "🧠 O que Atlas lembra" — a human-readable view of the memory backend (not a database browser:
 * no ids, tables, confidence scores as raw numbers). Lets the user actually see and seed memory
 * for the first time; the chat itself only ever *reads* this, it doesn't write back yet.
 */
@Composable
fun MemoryScreen(onBack: () -> Unit, viewModel: MemoryViewModel = hiltViewModel()) {
    var showAddDialog by remember { mutableStateOf(false) }

    // The ViewModel's init{} only fires once per instance, but the Activity's ViewModelStore keeps
    // that same instance alive across repeated visits to this screen (it's not a separate
    // destination in a NavHost) — without this, memory added since the *first* visit wouldn't show
    // up on a second visit without a full app restart.
    LaunchedEffect(Unit) { viewModel.reload() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                }
                Text(
                    text = "O que Atlas lembra",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { viewModel.dismissAddError(); showAddDialog = true }) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Adicionar")
                }
            }

            if (viewModel.error != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = viewModel.error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::reload) { Text("Tentar de novo") }
                }
            }
            // Only a cue for a reload that's re-running after data is already on screen — the
            // first-ever load (hasLoadedOnce == false) is instead covered per-section below, so a
            // category that's genuinely empty isn't momentarily indistinguishable from one that's
            // just slow to answer.
            if (viewModel.isLoading && viewModel.hasLoadedOnce) {
                Text(
                    text = "Atualizando…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                item { SectionHeader("Preferências", viewModel.preferences.size) }
                if (viewModel.preferences.isEmpty()) {
                    item { EmptyHint(loadingAwareEmptyText(viewModel, "Nenhuma preferência ainda")) }
                } else {
                    items(viewModel.preferences) { preference ->
                        MemoryRow(
                            title = preference.assunto,
                            subtitle = preference.preferencia,
                            onForget = { viewModel.forget("memory_preferences", preference.id!!) },
                        )
                    }
                }

                item { SectionHeader("Projetos", viewModel.projects.size) }
                if (viewModel.projects.isEmpty()) {
                    item { EmptyHint(loadingAwareEmptyText(viewModel, "Nenhum projeto ainda")) }
                } else {
                    items(viewModel.projects) { project: MemoryProject ->
                        val extra = when {
                            project.proximaAcao != null -> "Próxima ação: ${project.proximaAcao}"
                            project.objetivo != null -> project.objetivo
                            else -> project.aliases.joinToString().ifBlank { project.descricao.orEmpty() }
                        }
                        val subtitle = "[${project.status}]" + if (extra.isNullOrBlank()) "" else " · $extra"
                        MemoryRow(
                            title = project.nome,
                            subtitle = subtitle,
                            onForget = { viewModel.forget("memory_projects", project.id!!) },
                        )
                    }
                }

                item { SectionHeader("Fatos", viewModel.facts.size) }
                if (viewModel.facts.isEmpty()) {
                    item { EmptyHint(loadingAwareEmptyText(viewModel, "Nenhum fato ainda")) }
                } else {
                    items(viewModel.facts) { fact: MemoryFact ->
                        MemoryRow(
                            title = fact.chave ?: fact.tipo,
                            subtitle = runCatching { JSONObject(fact.conteudo).optString("valor").ifBlank { fact.conteudo } }.getOrDefault(fact.conteudo),
                            onForget = { viewModel.forget("memory_facts", fact.id!!) },
                        )
                    }
                }

                item { SectionHeader("Pessoas", viewModel.people.size) }
                if (viewModel.people.isEmpty()) {
                    item { EmptyHint(loadingAwareEmptyText(viewModel, "Nenhuma pessoa ainda")) }
                } else {
                    items(viewModel.people) { person: MemoryPerson ->
                        val subtitle = listOfNotNull(
                            person.relacao ?: person.contexto,
                            person.telefone,
                            person.assuntosAssociados.joinToString().ifBlank { null },
                        ).joinToString(" · ")
                        MemoryRow(
                            title = person.nome,
                            subtitle = subtitle,
                            onForget = { viewModel.forget("memory_people", person.id!!) },
                        )
                    }
                }

                item { SectionHeader("Decisões", viewModel.decisions.size) }
                if (viewModel.decisions.isEmpty()) {
                    item { EmptyHint(loadingAwareEmptyText(viewModel, "Nenhuma decisão ainda")) }
                } else {
                    items(viewModel.decisions) { decision: MemoryDecision ->
                        MemoryRow(
                            title = decision.regra,
                            subtitle = decision.periodo.orEmpty(),
                            onForget = { viewModel.forget("memory_decisions", decision.id!!) },
                        )
                    }
                }

                item { SectionHeader("Tarefas", viewModel.tasks.size) }
                if (viewModel.tasks.isEmpty()) {
                    item { EmptyHint(loadingAwareEmptyText(viewModel, "Nenhuma tarefa pendente")) }
                } else {
                    items(viewModel.tasks) { task: MemoryTask ->
                        TaskRow(
                            title = task.titulo,
                            subtitle = listOfNotNull(task.prazo?.let { "Prazo: $it" }, task.descricao).joinToString(" · "),
                            onComplete = { viewModel.completeTask(task.id!!) },
                            onForget = { viewModel.forget("memory_tasks", task.id!!) },
                        )
                    }
                }

                item { SectionHeader("Rotinas", viewModel.routines.size) }
                if (viewModel.routines.isEmpty()) {
                    item { EmptyHint(loadingAwareEmptyText(viewModel, "Nenhum padrão detectado ainda")) }
                } else {
                    items(viewModel.routines) { routine: MemoryRoutine ->
                        val horario = routine.horarioMedio?.take(5)?.let { "por volta das $it" }
                        val dias = formatRoutineDays(routine.dias).ifBlank { null }
                        val frequencia = "${(routine.frequencia * 100).roundToInt()}% dos dias"
                        val subtitle = listOfNotNull(horario, dias, frequencia).joinToString(" · ")
                        MemoryRow(
                            title = routineEventLabel(routine.evento).replaceFirstChar { it.uppercase() },
                            subtitle = subtitle,
                            onForget = { viewModel.forget("memory_routines", routine.id!!) },
                        )
                    }
                }

                item { SectionHeader("Episódios recentes", viewModel.episodes.size) }
                if (viewModel.episodes.isEmpty()) {
                    item { EmptyHint(loadingAwareEmptyText(viewModel, "Nenhum episódio ainda")) }
                } else {
                    items(viewModel.episodes) { episode: MemoryEpisode ->
                        MemoryRow(
                            title = episode.evento,
                            subtitle = episode.resultado.orEmpty(),
                            onForget = { viewModel.forget("memory_episodes", episode.id!!) },
                        )
                    }
                }

                item { SectionHeader("Histórico de correções", viewModel.corrections.size) }
                if (viewModel.corrections.isEmpty()) {
                    item { EmptyHint(loadingAwareEmptyText(viewModel, "Nenhuma correção ainda")) }
                } else {
                    items(viewModel.corrections) { correction: MemoryCorrection ->
                        CorrectionRow(correction)
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddMemoryDialog(
            isSaving = viewModel.isAddSaving,
            errorMessage = viewModel.addError,
            onDismiss = {
                showAddDialog = false
                viewModel.dismissAddError()
            },
            // Each onAddX below only closes the dialog once viewModel reports success — on
            // failure the dialog stays open, the typed fields are untouched (they live in
            // AddMemoryDialog's own `remember`, which this composable never leaves), and
            // viewModel.addError shows what went wrong so the user can just press Salvar again.
            onAddFact = { chave, valor -> viewModel.addFact(chave, valor) { success -> if (success) showAddDialog = false } },
            onAddPreference = { assunto, preferencia ->
                viewModel.addPreference(assunto, preferencia) { success -> if (success) showAddDialog = false }
            },
            onAddPerson = { nome, relacao -> viewModel.addPerson(nome, relacao) { success -> if (success) showAddDialog = false } },
            onAddDecision = { regra, periodo ->
                viewModel.addDecision(regra, periodo) { success -> if (success) showAddDialog = false }
            },
            onAddTask = { titulo, prazo -> viewModel.addTask(titulo, prazo) { success -> if (success) showAddDialog = false } },
        )
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Text(
        text = if (count > 0) "$title ($count)" else title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

/** Swaps an "empty" hint for a "still loading" one before the first [MemoryViewModel.reload] has
 * finished — without this every section reads "Nenhum/a ... ainda" the instant the screen opens,
 * indistinguishable from the user genuinely having nothing stored. */
private fun loadingAwareEmptyText(viewModel: MemoryViewModel, emptyText: String): String =
    if (viewModel.isLoading && !viewModel.hasLoadedOnce) "Carregando…" else emptyText

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun MemoryRow(title: String, subtitle: String, onForget: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle.isNotBlank()) {
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onForget) {
            Icon(imageVector = Icons.Default.Close, contentDescription = "Esquecer")
        }
    }
}

/** Same shape as [MemoryRow] plus a checkmark to mark the task done — distinct from "esquecer"
 * (which just removes the row): completing a task is itself worth keeping a record of having
 * happened, "esquecer" is for a task that was never actually going to happen. */
@Composable
private fun TaskRow(title: String, subtitle: String, onComplete: () -> Unit, onForget: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle.isNotBlank()) {
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onComplete) {
            Icon(imageVector = Icons.Default.Check, contentDescription = "Concluir")
        }
        IconButton(onClick = onForget) {
            Icon(imageVector = Icons.Default.Close, contentDescription = "Esquecer")
        }
    }
}

/** Read-only — a correction is a log entry, not something to "esquecer" the way an active memory
 * row is. Shows what changed when both the old and new values were recorded (chat tool calls fill
 * these in; the manual-add path only logs the reason, since the old value is already visible in
 * whichever section it lives in). */
@Composable
private fun CorrectionRow(correction: MemoryCorrection) {
    val change = if (correction.conhecimentoAntigo != null && correction.conhecimentoNovo != null) {
        "${correction.conhecimentoAntigo} → ${correction.conhecimentoNovo}"
    } else {
        correction.motivo ?: "Corrigido"
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(text = "${tableLabel(correction.tabelaOrigem)}: $change", style = MaterialTheme.typography.bodyMedium)
        val subtitle = listOfNotNull(
            correction.motivo.takeIf { correction.conhecimentoAntigo != null },
            correction.createdAt?.take(10),
        ).joinToString(" · ")
        if (subtitle.isNotBlank()) {
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun tableLabel(tabelaOrigem: String): String = when (tabelaOrigem) {
    "memory_facts" -> "Fato"
    "memory_preferences" -> "Preferência"
    "memory_people" -> "Pessoa"
    "memory_decisions" -> "Decisão"
    "memory_projects" -> "Projeto"
    "memory_tasks" -> "Tarefa"
    else -> tabelaOrigem
}

private enum class AddKind { FACT, PREFERENCE, PERSON, DECISION, TASK }

@Composable
private fun AddMemoryDialog(
    isSaving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onAddFact: (chave: String, valor: String) -> Unit,
    onAddPreference: (assunto: String, preferencia: String) -> Unit,
    onAddPerson: (nome: String, relacao: String) -> Unit,
    onAddDecision: (regra: String, periodo: String) -> Unit,
    onAddTask: (titulo: String, prazo: String) -> Unit,
) {
    var kind by remember { mutableStateOf(AddKind.FACT) }
    var label by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }

    val labelText = when (kind) {
        AddKind.FACT -> "Chave (ex: mora_em)"
        AddKind.PREFERENCE -> "Assunto (ex: respostas)"
        AddKind.PERSON -> "Nome"
        AddKind.DECISION -> "Regra (ex: não me ligar depois das 22h)"
        AddKind.TASK -> "Título da tarefa"
    }
    val valueText = when (kind) {
        AddKind.FACT -> "Valor"
        AddKind.PREFERENCE -> "Preferência"
        AddKind.PERSON -> "Relação (ex: esposa, amigo, chefe)"
        AddKind.DECISION -> "Período (opcional, ex: à noite)"
        AddKind.TASK -> "Prazo (opcional, formato AAAA-MM-DD)"
    }

    AlertDialog(
        // Dismissing mid-save would leave the add call running with nothing on screen to show its
        // result — the dialog only closes from Cancelar/the scrim while it isn't actually saving.
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Adicionar à memória") },
        text = {
            Column {
                // Five chips don't fit an AlertDialog's bounded width on a phone — Row neither
                // wraps nor scrolls on its own, which silently clipped "Decisão"/"Tarefa" off the
                // edge. Horizontal scroll (same pattern as SettingsScreen's LabelColorRow) keeps
                // every option reachable without needing a multi-line chip layout here.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    FilterChip(selected = kind == AddKind.FACT, onClick = { kind = AddKind.FACT }, label = { Text("Fato") })
                    FilterChip(selected = kind == AddKind.PREFERENCE, onClick = { kind = AddKind.PREFERENCE }, label = { Text("Preferência") })
                    FilterChip(selected = kind == AddKind.PERSON, onClick = { kind = AddKind.PERSON }, label = { Text("Pessoa") })
                    FilterChip(selected = kind == AddKind.DECISION, onClick = { kind = AddKind.DECISION }, label = { Text("Decisão") })
                    FilterChip(selected = kind == AddKind.TASK, onClick = { kind = AddKind.TASK }, label = { Text("Tarefa") })
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(labelText) },
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(valueText) },
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (isSaving) {
                    Row(modifier = Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Salvando…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                errorMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (kind) {
                        AddKind.FACT -> onAddFact(label.trim(), value.trim())
                        AddKind.PREFERENCE -> onAddPreference(label.trim(), value.trim())
                        AddKind.PERSON -> onAddPerson(label.trim(), value.trim())
                        AddKind.DECISION -> onAddDecision(label.trim(), value.trim())
                        AddKind.TASK -> onAddTask(label.trim(), value.trim())
                    }
                },
                enabled = !isSaving && label.isNotBlank() && (kind == AddKind.DECISION || kind == AddKind.TASK || value.isNotBlank()),
            ) { Text(if (isSaving) "Salvando…" else "Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSaving) { Text("Cancelar") } },
    )
}
