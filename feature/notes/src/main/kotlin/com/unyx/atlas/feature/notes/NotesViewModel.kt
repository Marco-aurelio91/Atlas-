package com.unyx.atlas.feature.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unyx.atlas.data.ai.AtlasChatClient
import com.unyx.atlas.data.ai.ChatMessage
import com.unyx.atlas.memory.MemoryNote
import com.unyx.atlas.memory.MemoryRepository
import com.unyx.atlas.memory.auth.AuthRepository
import com.unyx.atlas.memory.MemoryTask
import com.unyx.atlas.memory.NoteChecklistItem
import com.unyx.atlas.memory.toInsertJson
import com.unyx.atlas.security.AccountKeyNotActiveException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import javax.inject.Inject

/** Which horizontal chip is active on the grid screen. */
enum class NoteFilter { TODAS, FIXADAS, LISTAS, LEMBRETES, ARQUIVADAS }

/** Result of an in-flight ✨ AI action, shown in [NoteAiSheet]'s preview UI — nothing is written to
 * the note/tasks until the user confirms (see [NotesViewModel.confirmAiText]/[confirmExtractedTasks]). */
sealed interface AiResult {
    data class Text(val action: AiAction, val text: String) : AiResult
    data class Tasks(val tasks: List<ExtractedTask>) : AiResult
    data class Reminder(val whenIso: String) : AiResult
    data class Answer(val question: String, val text: String) : AiResult
}

enum class AiAction { RESUMIR, ORGANIZAR, CORRIGIR, EXTRAIR_TAREFAS, CRIAR_LEMBRETE, PERGUNTAR }

data class ExtractedTask(val titulo: String, val prazo: String?, val hora: String?)

/** Upper bound on how long a single autosave may take before the editor stops showing "Salvando…"
 * and surfaces a retryable error instead. */
private const val SAVE_TIMEOUT_MILLIS = 20_000L

/**
 * Owns the notes grid's list/filter/search state, the currently-open note draft (autosaved on
 * every edit — there's no "Salvar" button, per the design spec), and the ✨ menu's one-shot chat
 * calls. [chatClient] calls here never pass `tools` — these are single-turn requests (summarize,
 * fix text, extract structure), not the multi-round tool-calling loop feature:chat's ChatViewModel
 * drives; see AtlasChatClient.send's `tools = emptyList()` default. Plain text in, plain text out,
 * no vision, no streaming — exactly what [AtlasChatClient]'s Gemini fallback already supports in
 * full, so these actions keep working even when Claude itself is down (confirmed as a real gap: a
 * zero-balance Claude account silently broke every ✨ action here before this used the fallback-
 * aware client, with no Gemini retry the way feature:chat's own ViewModels already had).
 */
@HiltViewModel
class NotesViewModel @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val authRepository: AuthRepository,
    private val chatClient: AtlasChatClient,
    private val reminderScheduler: NoteReminderScheduler,
) : ViewModel() {

    var notes by mutableStateOf<List<MemoryNote>>(emptyList())
        private set
    var filter by mutableStateOf(NoteFilter.TODAS)
        private set
    var searchQuery by mutableStateOf("")
        private set
    var isLoading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Failure from a grid action (fixar/arquivar/restaurar/excluir) — distinct from [error] (the
     * list load itself), since those actions keep whatever list is already on screen and just need
     * a one-off "this one thing didn't work" surfaced, not a full error state swapped in. */
    var actionError by mutableStateOf<String?>(null)
        private set

    /** The note currently open in the editor — null means the grid is showing. A brand-new,
     * not-yet-persisted note has [MemoryNote.id] == null; the first autosave gives it one. */
    var draft by mutableStateOf<MemoryNote?>(null)
        private set

    var aiLoading by mutableStateOf(false)
        private set
    var aiError by mutableStateOf<String?>(null)
        private set
    var aiResult by mutableStateOf<AiResult?>(null)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set

    /** True when [saveError] is specifically "no active account key yet" — lets the editor offer an
     * "Abrir Configurações" shortcut instead of the usual (useless, here) "Tentar" retry button,
     * since retrying without first finishing the Segurança do Atlas setup will just fail again. */
    var saveNeedsSecuritySetup by mutableStateOf(false)
        private set
    var isSaving by mutableStateOf(false)
        private set
    var reminderWarning by mutableStateOf<String?>(null)
        private set

    val claudeConfigured: Boolean get() = chatClient.isConfigured

    private var saveJob: Job? = null

    /** The one in-flight list-fetching coroutine — [refresh] and [updateSearchQuery] both cancel
     * whatever's here before launching their own, so a plain refresh, a filter switch and a search
     * can never race each other and let a slower, earlier response land after a faster, later one
     * and silently replace the current (correct) list with stale data. */
    private var queryJob: Job? = null
    private val persistMutex = Mutex()

    init {
        refresh()
    }

    fun refresh() {
        queryJob?.cancel()
        queryJob = viewModelScope.launch {
            isLoading = true
            error = null
            runCatching {
                when (filter) {
                    NoteFilter.ARQUIVADAS -> memoryRepository.listArchivedNotes()
                    else -> memoryRepository.listNotes(includeArchived = false)
                }
            }.fold(
                onSuccess = { notes = applyClientFilter(it) },
                onFailure = { error = it.message ?: "Não consegui carregar as notas" },
            )
            isLoading = false
        }
    }

    /** Also used to filter [updateSearchQuery]'s results — search deliberately stays *within* the
     * active chip rather than crossing it (the alternative, searching everything regardless of
     * filter, would make the chip row visibly lie about what's on screen while a query is typed).
     * [memoryRepository]'s own `searchNotes` has no archive clause at all, so without this a search
     * made while "Arquivadas" is selected would mix in non-archived matches too. */
    private fun applyClientFilter(all: List<MemoryNote>): List<MemoryNote> = when (filter) {
        NoteFilter.FIXADAS -> all.filter { it.fixada }
        NoteFilter.LISTAS -> all.filter { it.tipo == "checklist" }
        NoteFilter.LEMBRETES -> all.filter { !it.lembreteEm.isNullOrBlank() }
        NoteFilter.ARQUIVADAS -> all.filter { it.arquivada }
        NoteFilter.TODAS -> all.filter { !it.arquivada }
    }

    /** Named [selectFilter], not `setFilter` — a `var filter` property already generates a JVM
     * `setFilter(NoteFilter)` accessor (even with `private set`, the signature still exists), which
     * a same-named function here would clash with. */
    fun selectFilter(newFilter: NoteFilter) {
        filter = newFilter
        refresh()
    }

    /** Debounced (~400ms) so every keystroke doesn't fire a request — title/content only, see
     * [MemoryRepository.searchNotes]'s own limitations note. Named [updateSearchQuery] for the same
     * JVM-signature-clash reason as [selectFilter]. */
    fun updateSearchQuery(query: String) {
        searchQuery = query
        if (query.isBlank()) {
            refresh()
            return
        }
        queryJob?.cancel()
        queryJob = viewModelScope.launch {
            delay(400)
            isLoading = true
            runCatching { memoryRepository.searchNotes(query) }.fold(
                onSuccess = { notes = applyClientFilter(it) },
                onFailure = { error = it.message ?: "Não consegui buscar" },
            )
            isLoading = false
        }
    }

    // ---- Grid actions (persist immediately, not debounced) ----

    fun togglePin(note: MemoryNote) = mutate(note, "Não consegui fixar a nota") { JSONObject().put("fixada", !note.fixada) }
    fun toggleArchive(note: MemoryNote) = mutate(note, "Não consegui arquivar a nota") { JSONObject().put("arquivada", !note.arquivada) }
    fun trash(note: MemoryNote, onResult: (Boolean) -> Unit = {}) =
        mutateSuspend(note, "Não consegui mover a nota para a lixeira", onResult) { memoryRepository.trashNote(it) }
    fun restore(note: MemoryNote, onResult: (Boolean) -> Unit = {}) =
        mutateSuspend(note, "Não consegui restaurar a nota", onResult) { memoryRepository.restoreNote(it) }
    fun deleteForever(note: MemoryNote, onResult: (Boolean) -> Unit = {}) =
        mutateSuspend(note, "Não consegui excluir a nota", onResult) { memoryRepository.deleteNotePermanently(it) }

    fun dismissActionError() {
        actionError = null
    }

    fun loadTrash(onResult: (List<MemoryNote>) -> Unit) {
        viewModelScope.launch {
            runCatching { memoryRepository.listTrashedNotes() }.onSuccess(onResult)
        }
    }

    private fun mutate(note: MemoryNote, failureMessage: String, fields: () -> JSONObject) {
        val id = note.id ?: return
        viewModelScope.launch {
            actionError = null
            runCatching { memoryRepository.updateNote(id, fields()) }
                .onFailure { actionError = it.message?.let { reason -> "$failureMessage: $reason" } ?: failureMessage }
            refresh()
        }
    }

    /** [onResult] reports whether the action actually succeeded — callers that optimistically
     * update their own local UI state (e.g. [feature.notes.NotesListScreen]'s trash sheet removing
     * a row) must gate that on this, not on the call having been made. */
    private fun mutateSuspend(
        note: MemoryNote,
        failureMessage: String,
        onResult: (Boolean) -> Unit = {},
        action: suspend (String) -> Unit,
    ) {
        val id = note.id ?: return
        viewModelScope.launch {
            actionError = null
            val result = runCatching { action(id) }
            result.onFailure { actionError = it.message?.let { reason -> "$failureMessage: $reason" } ?: failureMessage }
            refresh()
            onResult(result.isSuccess)
        }
    }

    // ---- Editor ----

    /** [note] null starts a brand-new note/checklist (see [MemoryNote.tipo]); non-null opens an
     * existing one for editing. */
    fun openEditor(note: MemoryNote?, tipo: String = "nota") {
        draft = note ?: MemoryNote(tipo = tipo)
        aiResult = null
        aiError = null
    }

    fun closeEditor() {
        saveJob?.cancel()
        val note = draft ?: return
        viewModelScope.launch {
            if (persist(note)) {
                draft = null
                refresh()
            }
        }
    }

    fun updateDraft(transform: (MemoryNote) -> MemoryNote) {
        val updated = draft?.let(transform) ?: return
        draft = updated
        scheduleSave(updated)
    }

    private fun scheduleSave(note: MemoryNote) {
        saveJob?.cancel()
        saveError = null
        saveJob = viewModelScope.launch {
            delay(500)
            persist(note)
        }
    }

    /** A brand-new note that's still entirely empty is never written — no point in a row for a note
     * the user opened and immediately backed out of without typing anything. */
    private suspend fun persist(note: MemoryNote): Boolean = persistMutex.withLock {
        isSaving = true
        try {
            saveError = null
            saveNeedsSecuritySetup = false
            val isEmpty = note.titulo.isNullOrBlank() &&
                note.conteudo.isNullOrBlank() &&
                note.checklist.isEmpty() &&
                note.tags.isEmpty() &&
                note.lembreteEm.isNullOrBlank()
            val id = note.id
            if (id == null && isEmpty) {
                saveError = null
                return@withLock true
            }

            // Bounded so a stalled connection can never leave the editor showing "Salvando…"
            // indefinitely — a real network timeout further down (OkHttp's own read timeout) would
            // eventually surface too, but only after 30s+ and with no visible feedback until then;
            // this fires sooner with a message the user can actually act on.
            val saved = withTimeout(SAVE_TIMEOUT_MILLIS) {
                if (id == null) {
                    memoryRepository.addNote(note)
                } else {
                    memoryRepository.updateNote(id, note.toInsertJson())
                    note
                }
            }

            val currentDraft = draft
            val draftChangedWhileSaving = currentDraft != null && currentDraft.copy(
                id = note.id,
                userId = note.userId,
                createdAt = note.createdAt,
                updatedAt = note.updatedAt,
            ) != note
            val latest = when {
                id == null && currentDraft != null && currentDraft.id == null -> currentDraft.copy(
                    id = saved.id,
                    userId = saved.userId,
                    createdAt = saved.createdAt,
                    updatedAt = saved.updatedAt,
                )
                id != null && currentDraft?.id == id -> currentDraft
                else -> saved
            }
            if (currentDraft != null && (currentDraft.id == id || (id == null && currentDraft.id == null))) {
                draft = latest
            }

            reminderWarning = null
            val persistedNote = if (draftChangedWhileSaving) saved else latest
            persistedNote.id?.let { savedId ->
                try {
                    val reminderAt = persistedNote.lembreteEm
                    if (reminderAt.isNullOrBlank()) {
                        reminderScheduler.cancel(savedId)
                    } else if (!reminderScheduler.schedule(persistedNote)) {
                        reminderWarning = "Nota salva, mas as notificações estão desativadas. Ative-as para receber o lembrete."
                    }
                } catch (failure: Exception) {
                    reminderWarning = failure.message?.let { "Nota salva, mas não consegui agendar o lembrete: $it" }
                        ?: "Nota salva, mas não consegui agendar o lembrete."
                }
            }

            if (draftChangedWhileSaving) scheduleSave(latest)
            true
        } catch (timeout: TimeoutCancellationException) {
            saveError = "A conexão está lenta demais para salvar agora. Toque em Tentar quando a rede melhorar."
            false
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: AccountKeyNotActiveException) {
            saveError = failure.message
            saveNeedsSecuritySetup = true
            false
        } catch (failure: Exception) {
            saveError = failure.message?.let { "Não consegui salvar a nota: $it" }
                ?: "Não consegui salvar a nota. Verifique a conexão e tente novamente."
            false
        } finally {
            isSaving = false
        }
    }

    fun retrySaveDraft() {
        draft?.let(::scheduleSave)
    }

    fun trashDraft() {
        val d = draft ?: return
        val id = d.id
        saveJob?.cancel()
        viewModelScope.launch {
            try {
                if (id != null) memoryRepository.trashNote(id)
                if (id != null) reminderScheduler.cancel(id)
                draft = null
                refresh()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                saveError = failure.message?.let { "Não consegui mover a nota para a lixeira: $it" }
                    ?: "Não consegui mover a nota para a lixeira."
            }
        }
    }

    fun archiveDraft() {
        val d = draft ?: return
        updateDraft { it.copy(arquivada = !d.arquivada) }
        closeEditor()
    }

    fun togglePinDraft() = updateDraft { it.copy(fixada = !it.fixada) }
    fun setDraftColor(cor: String?) = updateDraft { it.copy(cor = cor) }

    fun addChecklistItem(texto: String) = updateDraft { note ->
        if (texto.isBlank()) note else note.copy(checklist = note.checklist + NoteChecklistItem(texto = texto.trim(), ordem = note.checklist.size))
    }

    fun toggleChecklistItem(index: Int) = updateDraft { note ->
        note.copy(checklist = note.checklist.mapIndexed { i, item -> if (i == index) item.copy(concluido = !item.concluido) else item })
    }

    fun removeChecklistItem(index: Int) = updateDraft { note ->
        note.copy(checklist = note.checklist.filterIndexed { i, _ -> i != index })
    }

    fun addTag(tag: String) = updateDraft { note ->
        val t = tag.trim().lowercase()
        if (t.isBlank() || note.tags.contains(t)) note else note.copy(tags = note.tags + t)
    }

    fun removeTag(tag: String) = updateDraft { note -> note.copy(tags = note.tags - tag) }

    // ---- AI (✨) actions — one-shot Claude calls, no tool loop ----

    fun dismissAiResult() {
        aiResult = null
        aiError = null
    }

    private fun noteText(): String {
        val d = draft ?: return ""
        return buildString {
            d.titulo?.let { appendLine(it) }
            d.conteudo?.let { appendLine(it) }
            d.checklist.forEach { appendLine("- ${it.texto}") }
        }.trim()
    }

    private fun runAi(systemPrompt: String, onReply: (String) -> AiResult?) {
        val text = noteText()
        if (text.isBlank()) {
            aiError = "A nota está vazia — escreva algo primeiro."
            return
        }
        viewModelScope.launch {
            aiLoading = true
            aiError = null
            runCatching {
                chatClient.send(
                    history = listOf(ChatMessage(role = "user", content = text)),
                    accessToken = authRepository.currentAccessToken()
                        ?: error("Entre na sua conta para usar a IA nas notas."),
                    systemPrompt = systemPrompt,
                    tools = emptyList(),
                )
            }.fold(
                onSuccess = { reply -> aiResult = onReply(reply.text) },
                onFailure = { aiError = it.message ?: "Não consegui falar com o Atlas agora" },
            )
            aiLoading = false
        }
    }

    fun aiSummarize() = runAi(
        "Resuma a nota do usuário em até 3 frases curtas, em português. Responda só com o resumo, sem introdução.",
    ) { AiResult.Text(AiAction.RESUMIR, it.trim()) }

    fun aiFixText() = runAi(
        "Corrija a ortografia e a gramática do texto a seguir, em português, mantendo o sentido e o tom originais. Responda só com o texto corrigido.",
    ) { AiResult.Text(AiAction.CORRIGIR, it.trim()) }

    fun aiOrganize() = extractTasks()
    fun aiExtractTasks() = extractTasks()

    private fun extractTasks() = runAi(
        "Hoje é ${LocalDate.now()}. Leia o texto do usuário e extraia as tarefas/ações pendentes nele. " +
            "Responda APENAS com um JSON estrito no formato " +
            "{\"tarefas\":[{\"titulo\":\"...\",\"prazo\":\"YYYY-MM-DD ou null\",\"hora\":\"HH:MM ou null\"}]}, " +
            "sem markdown, sem texto fora do JSON. Se não houver nenhuma tarefa clara, responda {\"tarefas\":[]}.",
    ) { raw -> AiResult.Tasks(parseExtractedTasks(raw)) }

    fun aiCreateReminder() = runAi(
        "Hoje é ${LocalDate.now()}, agora são ${java.time.LocalTime.now().withNano(0)}. Leia a nota do usuário e identifique " +
            "a data/hora a que ela se refere. Responda APENAS com um datetime ISO-8601 (ex: 2026-09-20T18:00:00), " +
            "nada mais. Se não houver nenhuma referência de tempo clara, responda apenas null.",
    ) { raw ->
        val cleaned = raw.trim().removeSurrounding("\"")
        if (cleaned.equals("null", ignoreCase = true)) {
            aiError = "Não encontrei uma data/hora clara nessa nota."
            null
        } else {
            runCatching { OffsetDateTime.parse(cleaned) }.fold(
                onSuccess = { AiResult.Reminder(cleaned) },
                onFailure = {
                    runCatching { java.time.LocalDateTime.parse(cleaned).toString() }
                        .map { AiResult.Reminder(it) }
                        .getOrElse { aiError = "Não entendi a data/hora dessa vez."; null }
                },
            )
        }
    }

    fun aiAsk(question: String) {
        if (question.isBlank()) return
        val text = noteText()
        viewModelScope.launch {
            aiLoading = true
            aiError = null
            runCatching {
                chatClient.send(
                    history = listOf(ChatMessage(role = "user", content = question)),
                    accessToken = authRepository.currentAccessToken()
                        ?: error("Entre na sua conta para usar a IA nas notas."),
                    systemPrompt = "O usuário está perguntando sobre esta nota específica. Conteúdo da nota:\n\n$text\n\n" +
                        "Responda a pergunta do usuário com base só nessa nota, de forma direta e curta.",
                    tools = emptyList(),
                )
            }.fold(
                onSuccess = { aiResult = AiResult.Answer(question, it.text.trim()) },
                onFailure = { aiError = it.message ?: "Não consegui falar com o Atlas agora" },
            )
            aiLoading = false
        }
    }

    private fun parseExtractedTasks(raw: String): List<ExtractedTask> {
        val jsonStart = raw.indexOf('{')
        val jsonEnd = raw.lastIndexOf('}')
        if (jsonStart < 0 || jsonEnd < jsonStart) return emptyList()
        return runCatching {
            val obj = JSONObject(raw.substring(jsonStart, jsonEnd + 1))
            val arr: JSONArray = obj.optJSONArray("tarefas") ?: JSONArray()
            List(arr.length()) { i ->
                val t = arr.getJSONObject(i)
                ExtractedTask(
                    titulo = t.getString("titulo"),
                    prazo = t.optString("prazo").takeIf { it.isNotBlank() && it != "null" },
                    hora = t.optString("hora").takeIf { it.isNotBlank() && it != "null" },
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Writes confirmed [AiResult.Text] back into the draft — [replace] true overwrites the body,
     * false appends. Mirrors create_task's own tool convention: inferred/AI-authored content is
     * marked `origem = "inferred"`, `confirmedByUser = false` until the user edits it further. */
    fun confirmAiText(result: AiResult.Text, replace: Boolean) {
        updateDraft { note ->
            val newContent = if (replace || note.conteudo.isNullOrBlank()) result.text else "${note.conteudo}\n\n${result.text}"
            note.copy(conteudo = newContent)
        }
        dismissAiResult()
    }

    fun confirmExtractedTasks(tasks: List<ExtractedTask>) {
        viewModelScope.launch {
            tasks.forEach { task ->
                runCatching {
                    memoryRepository.addTask(
                        MemoryTask(
                            titulo = task.titulo,
                            descricao = task.hora?.let { "às $it" },
                            prazo = task.prazo,
                            origem = "inferred",
                            confirmedByUser = false,
                        ),
                    )
                }
            }
        }
        dismissAiResult()
    }

    fun confirmReminder(result: AiResult.Reminder) {
        updateDraft { it.copy(lembreteEm = result.whenIso) }
        dismissAiResult()
    }
}
