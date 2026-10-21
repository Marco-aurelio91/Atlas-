package com.unyx.atlas.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unyx.atlas.memory.MemoryCorrection
import com.unyx.atlas.memory.MemoryDecision
import com.unyx.atlas.memory.MemoryEpisode
import com.unyx.atlas.memory.MemoryFact
import com.unyx.atlas.memory.MemoryPerson
import com.unyx.atlas.memory.MemoryPreference
import com.unyx.atlas.memory.MemoryProject
import com.unyx.atlas.memory.MemoryRepository
import com.unyx.atlas.memory.MemoryRoutine
import com.unyx.atlas.memory.MemoryTask
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

/** Narrow, stable personal attributes — the architecture spec's "Identity" memory type (section 6:
 * nome/apelido/idioma/dispositivo/etc), distinct from general consolidated knowledge ("Semantic",
 * section 14 — e.g. "trabalha na Unyx"). A [MemoryFact] with one of these keys gets `tipo =
 * "identity"`; everything else gets `tipo = "semantic"`. Both `save_fact` (ChatViewModel) and the
 * manual "Fato" add-dialog (this file) share this so a fact is classified the same way regardless
 * of whether Atlas inferred it or the user typed it directly. */
private val IDENTITY_FACT_KEYS = setOf("nome", "apelido", "idioma", "regiao", "dispositivo", "fuso_horario")

internal fun factTipoFor(chave: String): String = if (chave.lowercase() in IDENTITY_FACT_KEYS) "identity" else "semantic"

/** Turns a routine's internal event key (see ChatViewModel's routineEventKey) into a short
 * Portuguese phrase readable in MemoryScreen or a Claude prompt — "open_app:WhatsApp" → "abrir
 * WhatsApp". Falls back to the raw key for anything unrecognized rather than hiding it. */
internal fun routineEventLabel(evento: String): String = when {
    evento.startsWith("open_app:") -> "abrir ${evento.removePrefix("open_app:")}"
    evento == "get_weather" -> "consultar o clima"
    evento == "set_alarm" -> "criar um alarme"
    evento.startsWith("set_volume:") -> "ajustar o volume (${evento.removePrefix("set_volume:")})"
    evento == "set_brightness" -> "ajustar o brilho"
    evento == "charging_started" -> "conectar o carregador"
    evento == "charging_stopped" -> "desconectar o carregador"
    evento.startsWith("bluetooth_connected:") -> "conectar ${evento.removePrefix("bluetooth_connected:")}"
    evento.startsWith("bluetooth_disconnected:") -> "desconectar ${evento.removePrefix("bluetooth_disconnected:")}"
    else -> evento
}

private val WEEKDAY_ABBR = mapOf(1 to "seg", 2 to "ter", 3 to "qua", 4 to "qui", 5 to "sex", 6 to "sáb", 7 to "dom")

/** ISO weekday numbers (1=Monday..7=Sunday) → a short Portuguese list, collapsing the common cases
 * since a weekday-only or every-day pattern is far more legible as "seg-sex"/"todos os dias" than
 * five/seven comma-separated abbreviations. */
internal fun formatRoutineDays(dias: List<Int>): String = when (dias.sorted()) {
    emptyList<Int>() -> ""
    listOf(1, 2, 3, 4, 5) -> "seg-sex"
    listOf(1, 2, 3, 4, 5, 6, 7) -> "todos os dias"
    else -> dias.sorted().mapNotNull { WEEKDAY_ABBR[it] }.joinToString(", ")
}

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memoryRepository: MemoryRepository,
) : ViewModel() {

    val facts = mutableStateListOf<MemoryFact>()
    val preferences = mutableStateListOf<MemoryPreference>()
    val projects = mutableStateListOf<MemoryProject>()
    val people = mutableStateListOf<MemoryPerson>()
    val decisions = mutableStateListOf<MemoryDecision>()
    val tasks = mutableStateListOf<MemoryTask>()
    val routines = mutableStateListOf<MemoryRoutine>()
    val episodes = mutableStateListOf<MemoryEpisode>()
    val corrections = mutableStateListOf<MemoryCorrection>()
    var isLoading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    /** False only before the very first [reload] has finished — [MemoryScreen] uses this to tell
     * "still loading for the first time" apart from "genuinely has nothing", since every category
     * starts out as an empty list and otherwise looks identical either way. */
    var hasLoadedOnce by mutableStateOf(false)
        private set

    /** State for [MemoryScreen]'s add dialog specifically — distinct from [error] (the list reload)
     * so a stale reload error can never show up inside the dialog, and vice versa. The dialog stays
     * open and its typed fields stay put for as long as [isAddSaving]/[addError] say to; it's
     * [MemoryScreen] that decides to close it, only once the add call reports success. */
    var isAddSaving by mutableStateOf(false)
        private set
    var addError by mutableStateOf<String?>(null)
        private set

    fun dismissAddError() {
        addError = null
    }

    private var reloadJob: Job? = null

    // No init{} reload here on purpose: MemoryScreen's LaunchedEffect(Unit) already calls reload()
    // on every entry (first visit included, since a Hilt ViewModel instance survives revisits to
    // this screen). Calling reload() from both places raced two concurrent clear()+addAll() runs
    // on the same mutableStateListOf, and depending on which network response landed first, a
    // single row could render twice.
    //
    // That's the same race a second kind of overlap could still cause: nothing used to stop two
    // reload() calls themselves from running concurrently (revisiting the screen quickly, or an
    // add/forget action's own reload() landing mid-flight from an earlier one). Each is 9 concurrent
    // per-category requests, and with no ordering guarantee between two whole reload() runs, a
    // slower/earlier response could land after a faster/later one and silently overwrite fresh data
    // with stale data — rows visibly appearing then reverting. Cancelling any in-flight reload
    // before starting a new one removes that: at most one reload's results can ever land.
    fun reload() {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            isLoading = true
            error = null
            runCatching {
                // Load each category concurrently and publish it as soon as its own request
                // completes. Awaiting deferred results in a fixed order delayed fast categories
                // (such as preferences) behind whichever earlier request was slowest.
                coroutineScope {
                    launch { memoryRepository.facts().also { facts.clear(); facts.addAll(it) } }
                    launch { memoryRepository.preferences().also { preferences.clear(); preferences.addAll(it) } }
                    launch { memoryRepository.projects().also { projects.clear(); projects.addAll(it) } }
                    launch { memoryRepository.people().also { people.clear(); people.addAll(it) } }
                    launch { memoryRepository.decisions().also { decisions.clear(); decisions.addAll(it) } }
                    launch { memoryRepository.openTasks().also { tasks.clear(); tasks.addAll(it) } }
                    launch { memoryRepository.routines().also { routines.clear(); routines.addAll(it) } }
                    launch { memoryRepository.recentEpisodes(10).also { episodes.clear(); episodes.addAll(it) } }
                    launch { memoryRepository.recentCorrections(10).also { corrections.clear(); corrections.addAll(it) } }
                }
            }.onFailure { error = it.message ?: "Falha ao carregar memória" }
            isLoading = false
            hasLoadedOnce = true
        }
    }

    /** Every manual add below first checks for an existing record and soft-invalidates it via
     * [MemoryRepository.correct] before inserting — mirrors the upsert-not-duplicate pattern
     * ChatViewModel's save_fact/save_preference/save_person/save_decision tools already use.
     * Without this, typing a fact here that Claude already knows (or vice versa) created a second,
     * contradictory row instead of correcting the first — the same duplicate-row bug the tool-call
     * path was fixed for earlier, just reachable from the other entry point into memory. */
    /** Looks up a possible existing record before a manual add. A failure here (network, auth) is
     * NOT "not found" — [block] above used `.getOrNull()`, which treated a failed lookup exactly
     * like a confirmed absence and went on to insert anyway, risking a duplicate row every time the
     * duplicate check itself couldn't complete. This rethrows instead, so the caller's own
     * `runCatching` aborts the whole add and reports the real error. */
    private suspend fun <T> requireLookup(lookup: suspend () -> T?): T? =
        runCatching { lookup() }.getOrElse { throw IllegalStateException("Não consegui verificar duplicidade: ${it.message ?: "erro de rede"}", it) }

    fun addFact(chave: String, valor: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            isAddSaving = true
            addError = null
            runCatching {
                val existing = requireLookup { memoryRepository.findFactByChave(chave) }
                existing?.id?.let { existingId ->
                    val oldValor = runCatching { JSONObject(existing.conteudo).optString("valor") }.getOrDefault(existing.conteudo)
                    memoryRepository.correct(
                        tabelaOrigem = "memory_facts",
                        registroId = existingId,
                        conhecimentoAntigo = "$chave = $oldValor",
                        conhecimentoNovo = "$chave = $valor",
                        motivo = "Atualizado manualmente pelo usuário",
                    )
                }
                memoryRepository.addFact(
                    MemoryFact(
                        tipo = factTipoFor(chave),
                        chave = chave,
                        conteudo = JSONObject().put("valor", valor).toString(),
                        origem = "user_explicit",
                        confidence = 1f,
                        confirmedByUser = true,
                    ),
                )
            }.onSuccess { reload() }.onFailure { addError = it.message ?: "Falha ao salvar" }
            isAddSaving = false
            onResult(addError == null)
        }
    }

    fun addPreference(assunto: String, preferencia: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            isAddSaving = true
            addError = null
            runCatching {
                val existing = requireLookup { memoryRepository.findPreferenceByAssunto(assunto) }
                existing?.id?.let { existingId ->
                    memoryRepository.correct(
                        tabelaOrigem = "memory_preferences",
                        registroId = existingId,
                        conhecimentoAntigo = "${existing.assunto}: ${existing.preferencia}",
                        conhecimentoNovo = "$assunto: $preferencia",
                        motivo = "Atualizado manualmente pelo usuário",
                    )
                }
                memoryRepository.addPreference(
                    MemoryPreference(
                        assunto = assunto,
                        preferencia = preferencia,
                        origem = "user_explicit",
                        confidence = 1f,
                        confirmedByUser = true,
                    ),
                )
            }.onSuccess { reload() }.onFailure { addError = it.message ?: "Falha ao salvar" }
            isAddSaving = false
            onResult(addError == null)
        }
    }

    fun addPerson(nome: String, relacao: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            isAddSaving = true
            addError = null
            runCatching {
                val existing = requireLookup { memoryRepository.findPersonByName(nome) }
                existing?.id?.let { existingId ->
                    memoryRepository.correct(
                        tabelaOrigem = "memory_people",
                        registroId = existingId,
                        conhecimentoAntigo = null,
                        conhecimentoNovo = null,
                        motivo = "Atualizado manualmente pelo usuário",
                    )
                }
                memoryRepository.addPerson(
                    MemoryPerson(
                        nome = nome,
                        relacao = relacao.ifBlank { null } ?: existing?.relacao,
                        telefone = existing?.telefone,
                        contexto = existing?.contexto,
                        origem = "user_explicit",
                        confidence = 1f,
                        confirmedByUser = true,
                    ),
                )
            }.onSuccess { reload() }.onFailure { addError = it.message ?: "Falha ao salvar" }
            isAddSaving = false
            onResult(addError == null)
        }
    }

    fun addDecision(regra: String, periodo: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            isAddSaving = true
            addError = null
            runCatching {
                val existing = requireLookup { memoryRepository.findDecisionByRegra(regra) }
                existing?.id?.let { existingId ->
                    memoryRepository.correct(
                        tabelaOrigem = "memory_decisions",
                        registroId = existingId,
                        conhecimentoAntigo = "${existing.regra}${existing.periodo?.let { " ($it)" } ?: ""}",
                        conhecimentoNovo = "$regra${periodo.ifBlank { null }?.let { " ($it)" } ?: ""}",
                        motivo = "Atualizado manualmente pelo usuário",
                    )
                }
                memoryRepository.addDecision(
                    MemoryDecision(
                        regra = regra,
                        periodo = periodo.ifBlank { null },
                        confirmada = true,
                        origem = "user_explicit",
                        confidence = 1f,
                        confirmedByUser = true,
                    ),
                )
            }.onSuccess { reload() }.onFailure { addError = it.message ?: "Falha ao salvar" }
            isAddSaving = false
            onResult(addError == null)
        }
    }

    fun addTask(titulo: String, prazo: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            isAddSaving = true
            addError = null
            runCatching {
                memoryRepository.addTask(
                    MemoryTask(
                        titulo = titulo,
                        prazo = prazo.ifBlank { null },
                        origem = "user_explicit",
                        confidence = 1f,
                        confirmedByUser = true,
                    ),
                )
            }.onSuccess { reload() }.onFailure { addError = it.message ?: "Falha ao salvar" }
            isAddSaving = false
            onResult(addError == null)
        }
    }

    fun completeTask(id: String) {
        viewModelScope.launch {
            runCatching {
                memoryRepository.updateTask(
                    id,
                    JSONObject().put("status", "completed").put("completed_at", java.time.Instant.now().toString()),
                )
            }.onSuccess { reload() }.onFailure { error = it.message ?: "Falha ao concluir" }
        }
    }

    fun forget(tabelaOrigem: String, registroId: String) {
        viewModelScope.launch {
            runCatching {
                memoryRepository.correct(
                    tabelaOrigem = tabelaOrigem,
                    registroId = registroId,
                    conhecimentoAntigo = null,
                    conhecimentoNovo = null,
                    motivo = "Removido pelo usuário",
                )
            }.onSuccess { reload() }.onFailure { error = it.message ?: "Falha ao remover" }
        }
    }
}
