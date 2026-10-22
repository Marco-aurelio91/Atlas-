package com.unyx.atlas.feature.chat

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unyx.atlas.data.AppRepository
import com.unyx.atlas.data.SettingsRepository
import com.unyx.atlas.data.ai.AtlasChatClient
import com.unyx.atlas.data.ai.ChatMessage
import com.unyx.atlas.data.ai.ClaudeAction
import com.unyx.atlas.data.ai.ClaudeReply
import com.unyx.atlas.data.ai.ClaudeTool
import com.unyx.atlas.data.ai.DEFAULT_SYSTEM_PROMPT
import com.unyx.atlas.data.ai.PersistedAction
import com.unyx.atlas.data.smartspace.WeatherRepository
import com.unyx.atlas.memory.MemoryContext
import com.unyx.atlas.memory.MemoryDecision
import com.unyx.atlas.memory.MemoryEpisode
import com.unyx.atlas.memory.MemoryEvent
import com.unyx.atlas.memory.MemoryFact
import com.unyx.atlas.memory.MemoryMeta
import com.unyx.atlas.memory.MemoryNote
import com.unyx.atlas.memory.NoteChecklistItem
import com.unyx.atlas.memory.MemoryPerson
import com.unyx.atlas.memory.MemoryPreference
import com.unyx.atlas.memory.MemoryProject
import com.unyx.atlas.memory.MemoryRepository
import com.unyx.atlas.ui.voice.VoiceController
import com.unyx.atlas.feature.chat.agent.AgentEventBus
import com.unyx.atlas.feature.chat.agent.ATLAS_TOOLS
import com.unyx.atlas.feature.chat.agent.AtlasToolExecutor
import com.unyx.atlas.feature.chat.agent.HIGH_IMPACT_TOOLS
import com.unyx.atlas.feature.chat.agent.LocalCommandRouter
import com.unyx.atlas.feature.chat.agent.ChatRequestPolicy
import com.unyx.atlas.feature.chat.agent.currentDateTimeContext
import com.unyx.atlas.feature.chat.agent.currentDeviceContext
import com.unyx.atlas.feature.chat.automation.Automation
import com.unyx.atlas.feature.chat.automation.AutomationAction
import com.unyx.atlas.feature.chat.automation.AutomationCondition
import com.unyx.atlas.feature.chat.automation.AutomationStore
import com.unyx.atlas.feature.chat.automation.AutomationTrigger
import com.unyx.atlas.feature.chat.automation.AutomationGeofenceManager
import com.unyx.atlas.feature.chat.automation.automationSummary
import com.unyx.atlas.feature.chat.automation.parseAutomationAction
import com.unyx.atlas.feature.chat.automation.parseAutomationTrigger
import com.unyx.atlas.memory.MemoryRoutine
import com.unyx.atlas.memory.MemoryTask
import com.unyx.atlas.memory.weightedScore
import com.unyx.atlas.memory.auth.AuthRepository
import com.unyx.atlas.memory.auth.AuthState
import com.unyx.atlas.model.AppItem
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import kotlin.math.roundToInt

/** Tools that change real device state (vs. the save_* memory tools, or get_weather's pure read) —
 * a successful call to one of these gets logged as a [MemoryEpisode] (see ChatViewModel.logEpisode).
 * Mirrors ChatScreen.kt's actionCardSpec() set exactly; keep both in sync if a new action tool is
 * ever added — this list decides what becomes "something that happened" in memory, that one decides
 * what gets a UI Action Card. */
private val ACTION_TOOLS = setOf("open_app", "set_alarm", "start_timer", "set_volume", "set_brightness", "send_whatsapp_message", "create_reminder", "call_person", "open_internet_panel")

private val ACTION_TOOL_LABELS = mapOf(
    "open_app" to "Abriu um aplicativo",
    "set_alarm" to "Criou um alarme",
    "start_timer" to "Iniciou um timer",
    "set_volume" to "Ajustou o volume",
    "set_brightness" to "Ajustou o brilho",
    "send_whatsapp_message" to "Preparou uma mensagem no WhatsApp",
    "create_reminder" to "Abriu a agenda para criar um evento",
    "call_person" to "Abriu o discador",
    "open_internet_panel" to "Abriu o painel de Internet",
)

/** Tool calls specific enough (and repeated enough in real use) to plausibly form a same-time-most-
 * days habit — feeds [MemoryRepository.logEventAndInferRoutine]. Deliberately narrower than
 * [ACTION_TOOLS]: send_whatsapp_message/create_reminder are one-off and contact/date-specific, not
 * the kind of recurring pattern routine inference is built to catch. */
private val ROUTINE_TOOLS = setOf("open_app", "get_weather", "set_alarm", "set_volume", "set_brightness")

/** The event key routine inference buckets by — sub-typed where the target actually varies the
 * habit (which app, which volume stream), left as a single bucket where it doesn't (an alarm's
 * exact label varies too much to be a useful key). Returns null for a tool/result this shouldn't
 * track at all (e.g. a failed call, or [name] not in [ROUTINE_TOOLS]). */
private fun routineEventKey(name: String, input: JSONObject, result: String): String? {
    if (name !in ROUTINE_TOOLS || result.startsWith("Não consegui")) return null
    return when (name) {
        // Reuses the resolved label baked into the success message ("Abri WhatsApp") rather than the
        // raw nome_app the user typed, so "whats app"/"WhatsApp"/"whatsapp" all collapse to one key.
        "open_app" -> result.removePrefix("Abri ").ifBlank { null }?.let { "open_app:$it" }
        "get_weather" -> "get_weather"
        "set_alarm" -> "set_alarm"
        "set_volume" -> "set_volume:${input.optString("tipo").lowercase().ifBlank { "media" }}"
        "set_brightness" -> "set_brightness"
        else -> null
    }
}

/** [promptText] is what actually goes into the system prompt; [labels] are short, user-facing names
 * for the same items (e.g. "mora_em", "Prefere: estilo_resposta", a person's name) — shown when the
 * user taps a reply's "🧠 Memória utilizada" caption, so that claim isn't just words with nothing
 * behind it. */
private data class MemoryEntry(
    val category: String,
    val label: String,
    val promptLine: String,
    val sourceTable: String? = null,
    val sourceId: String? = null,
)

private data class SelectedMemoryDigest(
    val promptText: String,
    val labels: List<String>,
    val usage: List<Pair<String, List<String>>>,
)

private data class MemoryDigest(val entries: List<MemoryEntry>) {
    fun forRequest(text: String): SelectedMemoryDigest? {
        if (ChatRequestPolicy.isPublicFacing(text)) return null
        val query = ChatRequestPolicy.normalize(text)
        if (query.isBlank()) return null
        val selectedCategories = buildSet {
            if (ChatRequestPolicy.isExplicitMemoryRecall(text) || listOf("sobre mim", "meu perfil", "quem sou").any(query::contains)) {
                addAll(setOf("fact", "preference", "project", "decision", "person", "place", "task", "routine", "working"))
            }
            if (listOf("projeto", "projetos", "produto").any(query::contains)) add("project")
            if (listOf("tarefa", "tarefas", "pendencia", "pendente", "prazo", "lembrete", "agenda").any(query::contains)) add("task")
            if (listOf("preferencia", "prefiro", "gosto", "meu jeito", "estilo de resposta").any(query::contains)) add("preference")
            if (listOf("decisao", "combinado", "regra que", "regra minha").any(query::contains)) add("decision")
            if (listOf("pessoa", "pessoas", "quem conheco", "contato").any(query::contains)) add("person")
            if (listOf("moro", "endereco", "onde fica", "meu endereco", "meu nome").any(query::contains)) addAll(setOf("fact", "place"))
            if (listOf("rotina", "costumo", "normalmente", "meu horario").any(query::contains)) add("routine")
            if (listOf("onde paramos", "em andamento", "proximo passo").any(query::contains)) add("working")
            if (listOf("sobre mim", "meu perfil", "quem sou", "meu nome", "o que sabe de mim").any(query::contains)) add("fact")
        }
        val selected = entries.filter { entry ->
            // Identity (nome, apelido, idioma...) always rides along — a handful of short lines,
            // and the one category where "only when the user explicitly asks about themselves"
            // reads as the assistant not actually knowing who it's talking to.
            entry.category == "identity" ||
                entry.category in selectedCategories ||
                ChatRequestPolicy.normalize(entry.label).let { label -> label.length >= 3 && query.contains(label) }
        }
        if (selected.isEmpty()) return null
        val usage = selected.mapNotNull { entry ->
            val table = entry.sourceTable ?: return@mapNotNull null
            val id = entry.sourceId ?: return@mapNotNull null
            table to id
        }.groupBy({ it.first }, { it.second }).map { it.key to it.value }
        return SelectedMemoryDigest(
            promptText = buildString {
                append("Contexto pessoal relevante a esta pergunta; use somente os itens que ajudarem a respondê-la. Para textos destinados a outras pessoas, não revele estes dados sem pedido explícito:")
                selected.forEach { append("\n").append(it.promptLine) }
            },
            labels = selected.map { it.label }.distinct(),
            usage = usage,
        )
    }
}

/** How urgently a nudge asks for attention (architecture spec section 15) — purely a rendering
 * hint in the chat (see ChatScreen's EmptyState); [ATTENTION]/[CRITICAL] are additionally pushed
 * as system notifications by [ProactiveNudgeWorker] when the app is closed. Ordered least to most
 * interruptive; [PASSIVE] is emitted for weak routines but filtered out of the UI, [SILENT] is
 * unused so far, kept so the scale doesn't need renumbering later. */
enum class ProactiveLevel { SILENT, PASSIVE, SUGGEST, ATTENTION, CRITICAL }

/** [text] is what's shown; [prompt] is what actually gets sent (as a real chat message, through
 * the normal executeTool pipeline) when the user taps it — see [ChatViewModel.proactiveNudges].
 * [sourceKey] identifies *what kind of thing* this nudge is about (e.g. "routine:abrir WhatsApp",
 * "task:overdue") independent of its exact wording — the stable key
 * [ChatViewModel.recordNudgeFeedback] logs accept/dismiss events under, so "toda vez que isso for
 * sugerido, o usuário dispensa" can be learned per *kind*, not per literal sentence (which changes
 * with the time of day, the task title, etc). */
data class ProactiveNudge(
    val text: String,
    val prompt: String,
    val level: ProactiveLevel,
    val sourceKey: String,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val memoryRepository: MemoryRepository,
    private val appRepository: AppRepository,
    private val weatherRepository: WeatherRepository,
    private val chatClient: AtlasChatClient,
    private val historyStore: ChatHistoryStore,
    private val automationStore: AutomationStore,
    private val automationGeofenceManager: AutomationGeofenceManager,
    private val toolExecutor: AtlasToolExecutor,
    private val localCommandRouter: LocalCommandRouter,
    private val agentEventBus: AgentEventBus,
    private val settingsRepository: SettingsRepository,
    val voiceSettings: VoiceSettings,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.state

    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var mfaCode by mutableStateOf("")
    var authError by mutableStateOf<String?>(null)
    var authBusy by mutableStateOf(false)
        private set

    val messages = mutableStateListOf<ChatMessage>()
    var automations by mutableStateOf<List<Automation>>(emptyList())
        private set
    var inputText by mutableStateOf("")
    var isSending by mutableStateOf(false)
        private set

    /** A picked photo staged for the *next* [sendMessage] call — already downscaled/compressed
     * JPEG, base64-encoded (see [attachImage]). Null means no attachment pending; cleared once the
     * message carrying it is sent. */
    var pendingImageBase64 by mutableStateOf<String?>(null)
        private set

    /** Decodes, downscales (max 768px on the long edge — plenty for Claude's vision, and keeps the
     * base64 payload this app persists locally from ballooning) and JPEG-compresses the picked
     * image, off the main thread. Silently leaves [pendingImageBase64] unchanged on failure (a
     * corrupt/unreadable URI) rather than surfacing a whole error flow for what's a lightweight
     * "attach a photo" affordance. */
    fun attachImage(uri: Uri) {
        viewModelScope.launch {
            val base64 = withContext(Dispatchers.IO) { runCatching { decodeAndCompressImage(appContext, uri) }.getOrNull() }
            if (base64 != null) {
                pendingImageBase64 = base64
                pendingPdfBase64 = null
                pendingPdfFileName = null
            }
        }
    }

    fun clearPendingImage() {
        pendingImageBase64 = null
    }

    /** A picked PDF staged for the next [sendMessage] call — raw bytes (no re-encoding possible
     * the way a photo gets compressed), base64-encoded (see [readAndEncodePdf]). Mutually
     * exclusive with [pendingImageBase64] — the attach picker offers one attachment at a time. */
    var pendingPdfBase64 by mutableStateOf<String?>(null)
        private set
    var pendingPdfFileName by mutableStateOf<String?>(null)
        private set

    /** Keeps the current staged attachment on failure and surfaces why the new PDF was rejected. */
    fun attachPdf(uri: Uri) {
        viewModelScope.launch {
            chatError = null
            try {
                val base64 = withContext(Dispatchers.IO) { readAndEncodePdf(appContext, uri) }
                pendingPdfBase64 = base64
                pendingPdfFileName = withContext(Dispatchers.IO) { displayNameFor(appContext, uri) }
                pendingImageBase64 = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: PdfTooLargeException) {
                chatError = "O PDF deve ter no máximo 10 MiB."
            } catch (_: Exception) {
                chatError = "Não foi possível ler o PDF selecionado."
            }
        }
    }

    fun clearPendingPdf() {
        pendingPdfBase64 = null
        pendingPdfFileName = null
    }

    /** Pinned conversations first, then most-recently-updated — the same ordering the drawer
     * shows. */
    val conversations = mutableStateListOf<ConversationSummary>()
    var currentConversationId by mutableStateOf("")
        private set

    private fun refreshConversations() {
        conversations.clear()
        conversations.addAll(
            historyStore.listConversations()
                .sortedWith(compareByDescending<ConversationSummary> { it.pinned }.thenByDescending { it.updatedAt }),
        )
    }

    fun switchConversation(id: String) {
        currentConversationId = id
        historyStore.setCurrentConversationId(id)
        messages.clear()
        messages.addAll(historyStore.loadMessages(id))
        chatError = null
    }

    fun newConversation() {
        val summary = historyStore.createConversation()
        refreshConversations()
        switchConversation(summary.id)
    }

    fun renameConversation(id: String, title: String) {
        if (title.isBlank()) return
        historyStore.renameConversation(id, title.trim())
        refreshConversations()
    }

    fun deleteConversation(id: String) {
        historyStore.deleteConversation(id)
        refreshConversations()
        if (currentConversationId == id) {
            val next = conversations.firstOrNull()?.id ?: historyStore.createConversation().id.also { refreshConversations() }
            switchConversation(next)
        }
    }

    fun togglePin(id: String) {
        val target = conversations.firstOrNull { it.id == id } ?: return
        historyStore.setPinned(id, !target.pinned)
        refreshConversations()
    }

    /** Plain-text transcript for [android.content.Intent.ACTION_SEND] — the export item 21 asks
     * for. Reads live [messages] for the currently-open conversation (no need to round-trip
     * through storage for what's already in memory); any other conversation is read fresh from
     * disk since it isn't loaded. */
    fun exportConversationText(id: String): String {
        val msgs = if (id == currentConversationId) messages.toList() else historyStore.loadMessages(id)
        val title = conversations.firstOrNull { it.id == id }?.title ?: "Conversa com Atlas"
        return buildString {
            appendLine(title)
            appendLine()
            msgs.forEach { m ->
                appendLine(if (m.role == "user") "Você:" else "Atlas:")
                appendLine(m.content)
                appendLine()
            }
        }
    }

    /** Which tool is actively executing right now, or null while just waiting on Claude itself
     * (request in flight / text being generated) — drives the THINKING vs. EXECUTING split in
     * [AtlasActivity]. Only meaningful while [isSending] is true. */
    var executingTool by mutableStateOf<String?>(null)
        private set
    var chatError by mutableStateOf<String?>(null)

    /** The "nome" identity fact ([MemoryViewModel]'s own `IDENTITY_FACT_KEYS`), first token only —
     * used solely for [ChatScreen]'s empty-state greeting ("Bom dia, Marco"); null (falls back to
     * the plain time-of-day greeting) until the user has told Atlas their name at least once,
     * either in chat (`save_fact`) or on the Memória screen's manual add dialog. */
    var userFirstName by mutableStateOf<String?>(null)
        private set

    /** Things worth mentioning before the user asks — shown above the empty-state greeting on a
     * fresh conversation only (see ChatScreen's EmptyState), never as an auto-sent chat message.
     * Tapping one sends [ProactiveNudge.prompt] as a real message through the normal pipeline —
     * this never executes an action directly, so there's exactly one code path that actually does
     * things (executeTool), not a second one duplicated for proactive suggestions.
     *
     * Two sources, each picked for having a real signal behind it rather than a guess:
     * - Task deadlines: unambiguous, DB-backed, a real date comparison.
     * - Routines: revisited from the original scoping (which rejected these outright — they only
     *   capture Atlas-initiated actions, e.g. asking Atlas to open an app, not organic home-screen
     *   use, so "você ainda não fez X hoje" would false-positive constantly on someone who just
     *   used the app directly). The fix is framing, not data: only ever suggested as an optional,
     *   time-proximate offer ("por volta das 7:15 você costuma... quer que eu ajude com isso
     *   agora?") within a tight window of the routine's usual time, never a claim about whether it
     *   already happened — sidesteps the false-positive problem instead of solving it, which is
     *   honest given what the data can and can't support. */
    var proactiveNudges by mutableStateOf<List<ProactiveNudge>>(emptyList())
        private set

    /** The exact system prompt text (persona + date/device context + memory digest + semantic
     * recall) most recently sent to Claude — surfaced read-only for the Debug screen (architecture
     * spec section 37's "contexto enviado ao modelo"). Never read by anything else in the app; this
     * exists purely so "what did Atlas actually see" isn't just inferred from its reply. */
    var lastSystemPromptSent by mutableStateOf<String?>(null)
        private set

    /** Text of the reply as it streams in, replaced (not appended) on every update — see
     * [AtlasChatClient.send]'s `onTextUpdate` doc for why replace-semantics naturally clears stale
     * text from an earlier tool-calling round. Null before the first delta of a round arrives (the
     * UI falls back to the plain "pensando…" row until then) and reset to null once the turn is
     * fully persisted as a real [ChatMessage], so there's no flash of duplicate content. */
    var streamingText by mutableStateOf<String?>(null)
        private set

    private val voiceController = VoiceController(appContext)

    /** True while [VoiceController] is actively capturing speech — drives [AtlasActivity.LISTENING]
     * and the full-screen listening overlay (design spec item 17). */
    var isListening by mutableStateOf(false)
        private set

    /** Live partial transcript shown in the listening overlay while the user is still talking —
     * cleared once listening ends, whether that was a real result, silence, or a cancel. */
    var voicePartialText by mutableStateOf("")
        private set

    /** Raw dB from the recognizer's RMS callback, for the overlay's core to visibly pulse with the
     * user's own voice (design spec item 17: "o núcleo deve reagir ao volume da voz"). */
    var voiceAmplitude by mutableFloatStateOf(0f)
        private set

    /** True while [VoiceController] is speaking a reply aloud — drives [AtlasActivity.SPEAKING].
     * Set either for a reply to a message that itself came in by voice (see [pendingVoiceReply] in
     * [dispatchSend]) or by an explicit manual "ouvir" tap on any message (see [speakMessage]) — a
     * typed message finishing on its own still shouldn't suddenly start talking unprompted. */
    var isSpeaking by mutableStateOf(false)
        private set

    /** Which message's text is the one [isSpeaking] is currently reading, when it was started via
     * [speakMessage] — lets a message's own action row show a "stop" state on exactly the bubble
     * that's playing, instead of a global indicator with no per-message correspondence. Null for
     * the hands-free voice-reply path (it has its own stop affordance in ChatHeader already). */
    var speakingMessageText by mutableStateOf<String?>(null)
        private set

    var voiceError by mutableStateOf<String?>(null)

    /** Marks the in-flight [sendMessage] call as voice-originated, so its reply gets spoken aloud
     * once it lands instead of just appearing as text — reset the moment that happens. */
    private var pendingVoiceReply = false

    // Compose-observable mirrors of VoiceSettings' persisted values — VoiceSettings itself is a
    // plain SharedPreferences wrapper (not Compose state) so the voice-settings dialog has
    // something to actually recompose on; the setters below keep both in sync, and every place
    // that reads voice behavior (the TTS-trigger site, the hands-free loop) reads voiceSettings
    // directly, so these are UI-reactivity mirrors, not a second source of truth.
    var speakRepliesEnabled by mutableStateOf(voiceSettings.speakReplies)
        private set
    var handsFreeModeEnabled by mutableStateOf(voiceSettings.handsFreeMode)
        private set
    var speechRateValue by mutableFloatStateOf(voiceSettings.speechRate)
        private set
    var confirmTranscriptEnabled by mutableStateOf(voiceSettings.confirmTranscript)
        private set
    var endpointSilenceMillis by mutableIntStateOf(voiceSettings.endpointSilenceMillis)
        private set
    var preferOfflineRecognition by mutableStateOf(voiceSettings.preferOfflineRecognition)
        private set
    var speechVoiceName by mutableStateOf(voiceSettings.speechVoiceName)
        private set
    var speechVoiceOptions by mutableStateOf<List<VoiceController.SpeechVoice>>(emptyList())
        private set
    var voiceReviewPending by mutableStateOf(false)
        private set

    fun setSpeakReplies(enabled: Boolean) {
        speakRepliesEnabled = enabled
        voiceSettings.speakReplies = enabled
        if (!enabled) stopSpeakingNow()
    }

    fun setHandsFreeMode(enabled: Boolean) {
        handsFreeModeEnabled = enabled
        voiceSettings.handsFreeMode = enabled
        if (!enabled) handsFreeRestart?.cancel()
    }

    fun setSpeechRate(rate: Float) {
        speechRateValue = rate
        voiceSettings.speechRate = rate
    }

    fun setConfirmTranscript(enabled: Boolean) {
        confirmTranscriptEnabled = enabled
        voiceSettings.confirmTranscript = enabled
    }

    fun setEndpointSilence(millis: Int) {
        endpointSilenceMillis = millis.coerceIn(800, 3000)
        voiceSettings.endpointSilenceMillis = endpointSilenceMillis
    }

    fun updateOfflineRecognitionPreference(enabled: Boolean) {
        preferOfflineRecognition = enabled
        voiceSettings.preferOfflineRecognition = enabled
    }

    fun setSpeechVoice(name: String) {
        speechVoiceName = name
        voiceSettings.speechVoiceName = name
    }

    fun loadSpeechVoices() {
        voiceController.availablePortugueseVoices { speechVoiceOptions = it }
    }

    fun isOnDeviceRecognitionAvailable(): Boolean = voiceController.isOnDeviceRecognitionAvailable()

    fun isHeadsetConnected(): Boolean = voiceController.isHeadsetConnected()

    fun previewSpeechVoice() {
        stopSpeakingNow()
        isSpeaking = true
        speakingMessageText = null
        voiceController.speak(
            "Olá, eu sou o Atlas. Esta é uma prévia da voz selecionada.",
            rate = voiceSettings.speechRate,
            voiceName = voiceSettings.speechVoiceName,
            onError = { voiceError = it; isSpeaking = false },
            onCancelled = { isSpeaking = false },
        ) {
            isSpeaking = false
        }
    }

    /** Starts one listening session and, on a real result, fills [inputText] and sends it — voice
     * input in this design is "just talk," not "talk, then still have to tap send" (matches the
     * spec's own flow diagram: LISTENING → TRANSCRIBING → THINKING, no separate confirm step). */
    var voiceInputPhase by mutableStateOf(com.unyx.atlas.ui.voice.VoiceInputPhase.IDLE)
        private set
    var isVoicePaused by mutableStateOf(false)
        private set
    private var voiceSessionTranscript = ""
    private var handsFreeRestart: kotlinx.coroutines.Job? = null
    private var voiceScreenActive = false
    // Caps how many times a hands-free turn will re-listen through plain silence in a row before
    // actually surfacing an error — without this a muted/broken mic would spin forever.
    private var handsFreeSilenceRetries = 0
    private companion object {
        const val MAX_HANDS_FREE_SILENCE_RETRIES = 2

        /** [buildMemoryDigest] gives a cold-start race (network/token not warmed up yet, so every
         * fetch throws) this many tries total before trusting an all-empty result as genuine. */
        const val MEMORY_DIGEST_ATTEMPTS = 3
        const val PROACTIVE_REFRESH_INTERVAL_MILLIS = 15 * 60 * 1000L

        /** How many recent accept/dismiss events [isSuppressedByRejection] looks at per sourceKey
         * — a window, not "ever", so a routine that stops being annoying (or changes) isn't
         * suppressed forever by old feedback. */
        const val NUDGE_FEEDBACK_WINDOW = 10

        /** [isSuppressedByRejection] never suppresses on fewer dismissals than this — a single
         * "agora não" is normal noise, not a verdict. */
        const val MIN_DISMISSALS_TO_SUPPRESS = 2

        /** [automationSuggestionFor]'s bar — deliberately higher than [routineNudgeFor]'s 0.4: this
         * offers to automate the routine outright (no daily tap needed at all), so it should only
         * fire once the pattern is clearly established, not just plausible. */
        const val AUTOMATION_SUGGESTION_MIN_FREQUENCY = 0.7f
    }

    fun setVoiceScreenActive(active: Boolean) {
        voiceScreenActive = active
        if (!active) {
            cancelVoiceInput()
            stopSpeakingNow()
        }
    }

    fun startVoiceInput() {
        if (isListening || isVoicePaused || isSending) return
        voiceReviewPending = false
        handsFreeRestart?.cancel()
        stopSpeakingNow()
        if (!voiceController.isAvailable()) {
            val message = "Reconhecimento de voz não disponível neste aparelho"
            voiceError = message
            return
        }
        voiceSessionTranscript = ""
        voiceError = null
        voicePartialText = ""
        beginVoiceSegment()
    }

    private fun beginVoiceSegment() {
        val previousText = voiceSessionTranscript
        voiceError = null
        voiceController.startListening(
            onPhase = {
                voiceInputPhase = it
                isListening = it != com.unyx.atlas.ui.voice.VoiceInputPhase.IDLE
            },
            onPartial = { voicePartialText = listOf(previousText, it).filter(String::isNotBlank).joinToString(" ") },
            onAmplitude = { voiceAmplitude = it },
            onFinal = { text ->
                handsFreeSilenceRetries = 0
                acceptVoiceTranscript(listOf(previousText, text).filter(String::isNotBlank).joinToString(" "))
            },
            onError = { message, recoverable ->
                val canRetry = recoverable && voiceScreenActive && voiceSettings.handsFreeMode &&
                    handsFreeSilenceRetries < MAX_HANDS_FREE_SILENCE_RETRIES
                if (canRetry) {
                    // A quiet pause is normal mid-conversation — re-arm the mic instead of
                    // dropping the user out of hands-free mode over it.
                    handsFreeSilenceRetries++
                    handsFreeRestart?.cancel()
                    handsFreeRestart = viewModelScope.launch {
                        kotlinx.coroutines.delay(400)
                        if (voiceScreenActive && voiceSettings.handsFreeMode && !isSending && !isListening && !isSpeaking) {
                            if (voiceSessionTranscript.isBlank()) startVoiceInput() else beginVoiceSegment()
                        }
                    }
                } else {
                    handsFreeSilenceRetries = 0
                    voiceError = message
                    if (previousText.isNotBlank()) {
                        voiceSessionTranscript = previousText
                        voicePartialText = previousText
                        isVoicePaused = true
                    }
                }
            },
            onEnd = {
                isListening = false
            },
            onPaused = { text ->
                voiceSessionTranscript = listOf(previousText, text).filter(String::isNotBlank).joinToString(" ")
                voicePartialText = voiceSessionTranscript
                isVoicePaused = true
            },
            preferOffline = voiceSettings.preferOfflineRecognition,
            endpointSilenceMillis = voiceSettings.endpointSilenceMillis,
        )
    }

    private fun acceptVoiceTranscript(text: String) {
        isVoicePaused = false
        voiceSessionTranscript = ""
        voicePartialText = ""
        if (text.isBlank()) {
            voiceError = "Não reconheci nenhuma fala. Tente novamente."
            return
        }
        inputText = text
        if (voiceSettings.confirmTranscript) {
            voiceReviewPending = true
        } else {
            pendingVoiceReply = true
            sendMessage()
        }
    }

    fun pauseVoiceInput() = voiceController.pauseListening()

    fun resumeVoiceInput() {
        if (!isVoicePaused || isListening || isSending) return
        isVoicePaused = false
        beginVoiceSegment()
    }

    fun finishVoiceInput() {
        if (isVoicePaused) acceptVoiceTranscript(voiceSessionTranscript)
        else voiceController.finishListening()
    }

    fun discardVoiceDraft() {
        voiceReviewPending = false
        inputText = ""
    }

    fun cancelVoiceInput() {
        handsFreeRestart?.cancel()
        voiceController.stopListening()
        isListening = false
        isVoicePaused = false
        voiceSessionTranscript = ""
        voicePartialText = ""
    }

    /** Interrupts Atlas mid-sentence — there was previously no way to do this at all; a spoken
     * reply always ran to completion. `TextToSpeech.stop()` does NOT reliably fire the utterance
     * listener's onDone/onError for the interrupted utterance (that's only guaranteed for an
     * utterance that finishes or errors on its own), so [isSpeaking] is reset here directly rather
     * than trusting [VoiceController.speak]'s completion callback to run. */
    fun stopSpeakingNow() {
        handsFreeRestart?.cancel()
        pendingVoiceReply = false
        voiceController.stopSpeaking()
        if (isSpeaking) {
            isSpeaking = false
        }
        speakingMessageText = null
    }

    /** Manual "ouvir" on one specific message's own action row — independent of the
     * voice-input-in → spoken-reply-out flow ([pendingVoiceReply]) a hands-free turn uses; this
     * reads back ANY assistant message, typed or voice-originated, on demand. Tapping it again on
     * the SAME message stops it; tapping it on a different message interrupts the first and starts
     * the new one (matches [VoiceController.speak]'s own QUEUE_FLUSH behavior). */
    fun speakMessage(text: String) {
        if (isSpeaking && speakingMessageText == text) {
            stopSpeakingNow()
            return
        }
        isSpeaking = true
        speakingMessageText = text
        voiceController.speak(
            text = text,
            rate = voiceSettings.speechRate,
            voiceName = voiceSettings.speechVoiceName,
            onError = { message -> isSpeaking = false; speakingMessageText = null; voiceError = message },
            onCancelled = { isSpeaking = false; speakingMessageText = null },
            onDone = { isSpeaking = false; speakingMessageText = null },
        )
    }

    override fun onCleared() {
        super.onCleared()
        voiceController.destroy()
    }

    val aiGatewayConfigured: Boolean get() = chatClient.isConfigured

    // Fetched once per sign-in and cached between turns. A failed load can be retried in the
    // background when needed; successful memory writes and edits trigger an explicit refresh.
    private var memoryContext: MemoryDigest? = null
    private var memoryDigestLoadJob: kotlinx.coroutines.Job? = null
    private var memoryDigestReloadQueued = false
    private var proactiveRefreshJob: kotlinx.coroutines.Job? = null

    // Bootstraps the current conversation. Deliberately placed after every property this class
    // declares (rather than up near `conversations`/`currentConversationId`, which would read more
    // naturally) — switchConversation() below also touches `chatError`, and Kotlin runs init blocks
    // interleaved with property initializers in textual order: an init block positioned *before* a
    // property it reaches into finds that property's mutableStateOf delegate still null (the
    // backing field hasn't been assigned yet), which crashed every single open of the chat screen
    // with a NullPointerException on MutableState.setValue until this was moved down here.
    init {
        refreshAutomations()
        refreshConversations()
        val id = historyStore.currentConversationId()?.takeIf { cid -> conversations.any { it.id == cid } }
            ?: conversations.firstOrNull()?.id
            ?: historyStore.createConversation().id.also { refreshConversations() }
        switchConversation(id)
        subscribeToAgentEvents()
    }

    fun refreshAutomations() {
        automations = automationStore.all()
    }

    fun setAutomationEnabled(id: String, enabled: Boolean) {
        automationStore.setEnabled(id, enabled)
        automationGeofenceManager.sync()
        refreshAutomations()
    }

    fun updateAutomation(automation: Automation) {
        automationStore.update(automation)
        automationGeofenceManager.sync()
        refreshAutomations()
    }

    fun deleteAutomation(id: String) {
        automationStore.remove(id)
        automationGeofenceManager.sync()
        refreshAutomations()
    }

    fun createAutomation(automation: Automation) {
        automationStore.add(automation)
        automationGeofenceManager.sync()
        refreshAutomations()
    }

    // Guards pruneExpiredMemory() below so it runs once per app session, not on every LoggedIn
    // re-emission (authState can fire again on a token refresh, not just the initial sign-in).
    private var prunedExpiredMemoryThisSession = false

    // Same once-per-session guard, separate flag: the name fact itself can't change mid-session
    // from this screen (only the Memória screen's add dialog or save_fact write it), so there's no
    // reason to refetch it on every token refresh.
    private var loadedUserFirstNameThisSession = false

    init {
        viewModelScope.launch {
            authState.collect { state ->
                if (state is AuthState.LoggedIn) {
                    loadMemoryDigest(force = true)
                    if (!loadedUserFirstNameThisSession) {
                        loadedUserFirstNameThisSession = true
                        loadUserFirstName()
                    }
                    if (!prunedExpiredMemoryThisSession) {
                        prunedExpiredMemoryThisSession = true
                        pruneExpiredMemory()
                        computeProactiveNudges()
                    }
                }
            }
        }
    }

    private fun loadUserFirstName() {
        viewModelScope.launch {
            val fact = runCatching { memoryRepository.findFactByChave("nome") }.getOrNull()
            val value = fact?.let { runCatching { JSONObject(it.conteudo).optString("valor") }.getOrNull() }
            userFirstName = value?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() }
        }
    }

    /** Fire-and-forget, once per session (see [prunedExpiredMemoryThisSession]) — deletes raw
     * events/context snapshots whose expiry already passed (see [MemoryRepository.pruneExpired]).
     * Cheap enough and infrequent enough not to need its own scheduler; opportunistic on sign-in is
     * the natural "once in a while, not on every message" moment this app already has. */
    private fun pruneExpiredMemory() {
        viewModelScope.launch {
            runCatching { memoryRepository.pruneExpired("memory_events") }
            runCatching { memoryRepository.pruneExpired("memory_context") }
            // Decay/desaprendizado (plan section 20) — see MemoryRepository.decayStaleMemory's own
            // doc for why this only touches facts/preferences and only under its conservative
            // all-of-these-conditions bar.
            runCatching { memoryRepository.decayStaleMemory("memory_facts") }
            runCatching { memoryRepository.decayStaleMemory("memory_preferences") }
        }
    }

    /** Fire-and-forget, once per session (plus after task-touching tool calls, via
     * refreshMemoryContext) — rebuilds [proactiveNudges] from two independent sources: task
     * deadlines and, new to this check-in, a time-proximate routine suggestion. At most one
     * task-derived nudge and one routine-derived nudge, so the empty state never turns into a wall
     * of suggestions — a single relevant thing said clearly beats several said quietly. */
    private fun computeProactiveNudges() {
        viewModelScope.launch {
            val candidates = mutableListOf<ProactiveNudge>()

            val tasks = runCatching { memoryRepository.openTasks() }.getOrDefault(emptyList())
            // Projects carry a deadline too (Goal Model, plan section 25), so tasks and projects
            // feed the same overdue/due-today nudges — see [deadlineNudges], shared with the
            // background [ProactiveNudgeWorker] so both surfaces always agree.
            val projects = runCatching { memoryRepository.projects() }.getOrDefault(emptyList())
            candidates += deadlineNudges(tasks, projects, java.time.LocalDate.now())

            val routines = runCatching { memoryRepository.routines() }.getOrDefault(emptyList())
            val now = java.time.LocalDateTime.now()
            // A routine strong enough to suggest automating outranks just suggesting the one-off
            // action — see [automationSuggestionFor]'s higher bar. Only one routine-derived slot
            // total (same "one relevant thing beats several" discipline as the task nudge above).
            val routineNudge = routines.asSequence().mapNotNull { automationSuggestionFor(it) }.firstOrNull()
                ?: routines.asSequence().mapNotNull { routineNudgeFor(it, now) }.firstOrNull()
            routineNudge?.let { candidates += it }

            // Learned suppression (architecture spec section 16) — filtered last, after picking
            // candidates, so a routine that's currently suppressed doesn't block a *different*
            // routine from getting the one slot (see [routineNudgeFor]'s firstOrNull above).
            // CRITICAL is exempt — an overdue task doesn't stop mattering just because the user
            // dismissed a routine suggestion a few times (the Attention Model's own point: CRITICAL
            // means "act now regardless", not "a nudge like any other" — section 19).
            val updated = candidates.filterNot { it.level != ProactiveLevel.CRITICAL && isSuppressedByRejection(it.sourceKey) }
            val previousNudges = proactiveNudges.map { it.sourceKey to it.text }.toSet()
            proactiveNudges = updated
            if (updated.any { (it.sourceKey to it.text) !in previousNudges }) {
                val overlayEnabled = runCatching { settingsRepository.overlayEnabled.first() }
                    .onFailure { android.util.Log.w("ChatViewModel", "Could not read the Atlas overlay preference.", it) }
                    .getOrDefault(false)
                if (overlayEnabled) AtlasOverlayService.showProactiveEvent(appContext)
            }
        }
    }

    /** Whether [sourceKey] has been dismissed clearly more than it's been accepted recently — the
     * "reduz sugestões repetidamente rejeitadas" half of section 16. Requires at least
     * [MIN_DISMISSALS_TO_SUPPRESS] dismissals before ever suppressing anything: one "agora não"
     * is normal noise, not a verdict, and shouldn't silence a genuinely useful nudge forever. Reads
     * the same memory_events rows [recordNudgeFeedback] writes — no separate counter/table. */
    private suspend fun isSuppressedByRejection(sourceKey: String): Boolean {
        val dismissed = runCatching {
            memoryRepository.eventsByType("nudge_dismissed:$sourceKey", limit = NUDGE_FEEDBACK_WINDOW)
        }.getOrDefault(emptyList())
        if (dismissed.size < MIN_DISMISSALS_TO_SUPPRESS) return false
        val accepted = runCatching {
            memoryRepository.eventsByType("nudge_accepted:$sourceKey", limit = NUDGE_FEEDBACK_WINDOW)
        }.getOrDefault(emptyList())
        return dismissed.size > accepted.size
    }

    /** Fire-and-forget: logs one accept/dismiss touch on a nudge, keyed by [ProactiveNudge.sourceKey]
     * — reuses the plain memory_events pipeline (same shape as any other observed event) rather
     * than a dedicated feedback table, since [isSuppressedByRejection] only ever needs a count. */
    private fun recordNudgeFeedback(nudge: ProactiveNudge, accepted: Boolean) {
        viewModelScope.launch {
            val key = if (accepted) "nudge_accepted:${nudge.sourceKey}" else "nudge_dismissed:${nudge.sourceKey}"
            runCatching { memoryRepository.logEvent(MemoryEvent(evento = key, importance = 0.1f)) }
        }
    }

    /** Tapping a nudge sends it as a real message (already handled by the caller's onSuggestionClick
     * wiring — see ChatScreen); this only records the acceptance signal alongside that. */
    fun recordNudgeAccepted(nudge: ProactiveNudge) {
        recordNudgeFeedback(nudge, accepted = true)
    }

    /** Removes [nudge] from the visible list immediately (optimistic — no reason to wait on a
     * network round trip to stop showing something the user just said "not now" to) and records
     * the rejection signal [isSuppressedByRejection] will read back later. */
    fun dismissNudge(nudge: ProactiveNudge) {
        proactiveNudges = proactiveNudges.filterNot { it === nudge }
        recordNudgeFeedback(nudge, accepted = false)
    }

    /** A routine only becomes a nudge when it's both established (frequência ≥ 40% — a higher bar
     * than the chat digest's 25%, since this is front-and-center UI, not background context) and
     * time-proximate (within 90 minutes of its usual time either side) — the mechanism that lets
     * this be genuinely useful without claiming to know whether today's occurrence already
     * happened (see [proactiveNudges]'s doc). Only `open_app`/`get_weather` routines get a nudge:
     * `set_alarm`/`set_volume`/`set_brightness` need a parameter (which hour, which level) the
     * routine's event key doesn't capture, so there's no unambiguous "do it now" action to offer. */
    private fun routineNudgeFor(routine: MemoryRoutine, now: java.time.LocalDateTime): ProactiveNudge? {
        // Below 0.25 (the same floor buildMemoryDigest already uses to even mention a routine as
        // context) there's too little signal to call it a routine at all. [0.25, 0.4) is real but
        // weak — PASSIVE, computed and available but not surfaced as an interrupting card (see
        // ChatScreen's own filter) — section 19's "BACKGROUND → não interromper". 0.4+ is SUGGEST,
        // as before.
        if (routine.frequencia < 0.25f) return null
        val level = if (routine.frequencia < 0.4f) ProactiveLevel.PASSIVE else ProactiveLevel.SUGGEST
        if (routine.dias.isNotEmpty() && now.dayOfWeek.value !in routine.dias) return null
        val horario = routine.horarioMedio?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() } ?: return null
        val diffMinutes = kotlin.math.abs(java.time.Duration.between(horario, now.toLocalTime()).toMinutes())
        if (diffMinutes > 90) return null
        val prompt = when {
            routine.evento.startsWith("open_app:") -> "Abre o ${routine.evento.removePrefix("open_app:")}"
            routine.evento == "get_weather" -> "Qual é o clima agora?"
            else -> return null
        }
        val horarioLabel = horario.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        return ProactiveNudge(
            text = "Por volta das $horarioLabel você costuma ${routineEventLabel(routine.evento)} — quer que eu ajude com isso agora?",
            prompt = prompt,
            level = level,
            sourceKey = "routine:${routine.evento}",
        )
    }

    /**
     * Automation Learning (architecture spec section 17), scoped to the one case where both the
     * trigger *and* the action are already fully known from a routine alone — `open_app:X` at a
     * consistent daily time maps directly onto [AutomationTrigger.DailyTime] +
     * [AutomationAction.OpenApp], the exact shape [AutomationStore]/`create_automation` already
     * execute. Every other routine kind (bluetooth, charging, weather) only tells us the *trigger*
     * happens often — not what the user wants to happen as a result, which nothing here can guess.
     *
     * "Nunca converter automaticamente rotina em ação sensível" (section 17) is honored by
     * construction: this never calls [AutomationStore] itself, only returns a [ProactiveNudge]
     * whose prompt — if the user taps it — goes through the exact same chat/tool-confirmation path
     * as typing the request by hand. Declining twice suppresses it via the same
     * [isSuppressedByRejection] mechanism as any other nudge.
     */
    private fun automationSuggestionFor(routine: MemoryRoutine): ProactiveNudge? {
        if (routine.frequencia < AUTOMATION_SUGGESTION_MIN_FREQUENCY) return null
        if (!routine.evento.startsWith("open_app:")) return null
        val appLabel = routine.evento.removePrefix("open_app:")
        val horario = routine.horarioMedio?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() } ?: return null

        // Don't suggest what's already automated — a DailyTime automation within 30 minutes of
        // this routine's usual time, opening the same app, counts as "already covered" even if the
        // user set the exact minute slightly differently than the routine's observed average.
        val alreadyAutomated = automationStore.all().any { automation ->
            val trigger = automation.trigger as? AutomationTrigger.DailyTime ?: return@any false
            val action = automation.action as? AutomationAction.OpenApp ?: return@any false
            action.appLabel.equals(appLabel, ignoreCase = true) &&
                kotlin.math.abs(java.time.Duration.between(horario, java.time.LocalTime.of(trigger.hour, trigger.minute)).toMinutes()) <= 30
        }
        if (alreadyAutomated) return null

        val horarioLabel = horario.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val diasLabel = formatRoutineDays(routine.dias).ifBlank { null }?.let { " ($it)" } ?: ""
        return ProactiveNudge(
            text = "Você abre o $appLabel quase sempre por volta das $horarioLabel$diasLabel — quer que eu crie uma automação pra isso?",
            prompt = "Cria uma automação: todo dia às $horarioLabel, abrir $appLabel",
            level = ProactiveLevel.SUGGEST,
            sourceKey = "automation_suggestion:${routine.evento}",
        )
    }

    /** Re-fetches the memory digest on demand — called after the user edits memory directly (see
     * MemoryScreen) so a fact/preference added there is reflected in the very next chat turn,
     * instead of only refreshing on the next sign-in. */
    fun refreshMemoryContext() {
        if (authState.value !is AuthState.LoggedIn) return
        loadMemoryDigest(force = true)
        computeProactiveNudges()
    }

    fun onChatResumed() {
        refreshMemoryContext()
        if (proactiveRefreshJob?.isActive == true) return
        proactiveRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(PROACTIVE_REFRESH_INTERVAL_MILLIS)
                if (authState.value is AuthState.LoggedIn) computeProactiveNudges()
            }
        }
    }

    fun onChatPaused() {
        proactiveRefreshJob?.cancel()
        proactiveRefreshJob = null
    }

    fun publishOverlayActivity(activity: AtlasActivity) {
        viewModelScope.launch {
            val enabled = runCatching { settingsRepository.overlayEnabled.first() }
                .onFailure { android.util.Log.w("ChatViewModel", "Could not read the Atlas overlay preference.", it) }
                .getOrDefault(false)
            if (enabled) AtlasOverlayService.updateActivity(appContext, activity)
        }
    }

    /** Loads memory away from the response path. Coalesces ordinary warmups, while a forced refresh
     * requested during an active load gets one follow-up pass so a just-saved item is not missed. */
    private fun loadMemoryDigest(force: Boolean = false) {
        if (!force && memoryContext != null) return
        if (memoryDigestLoadJob?.isActive == true) {
            if (force) memoryDigestReloadQueued = true
            return
        }
        memoryDigestLoadJob = viewModelScope.launch {
            do {
                memoryDigestReloadQueued = false
                memoryContext = buildMemoryDigest()
            } while (memoryDigestReloadQueued)
        }
    }

    /** A short, compact digest of what Atlas already knows — only the highest-importance items,
     * to keep this from silently ballooning the size (and cost) of every single request. Returns
     * an empty digest when the store has no entries, and null only when every attempt fails. All
     * fetches run concurrently (see MemoryViewModel.reload()'s equivalent fix).
     *
     * Retries a failed fetch batch before trusting an empty result. The first call happens during sign-in,
     * while the app is cold-starting; [anyFailure] distinguishes a network/token race from a
     * genuinely empty memory store so only failed fetches are retried. */
    private suspend fun buildMemoryDigest(): MemoryDigest? {
        repeat(MEMORY_DIGEST_ATTEMPTS) { attempt ->
            var anyFailure = false
            suspend fun <T> safely(default: T, block: suspend () -> T): T =
                runCatching { block() }.getOrElse { anyFailure = true; default }

            val digest = coroutineScope {
                val factsDeferred = async { safely(emptyList()) { memoryRepository.facts() } }
                val preferencesDeferred = async { safely(emptyList()) { memoryRepository.preferences() } }
                val projectsDeferred = async { safely(emptyList()) { memoryRepository.projects() } }
                val decisionsDeferred = async { safely(emptyList()) { memoryRepository.decisions() } }
                val peopleDeferred = async { safely(emptyList()) { memoryRepository.people() } }
                val placesDeferred = async { safely(emptyList()) { memoryRepository.places() } }
                val tasksDeferred = async { safely(emptyList()) { memoryRepository.openTasks() } }
                val routinesDeferred = async { safely(emptyList()) { memoryRepository.routines() } }
                val workingMemoryDeferred = async { safely(null) { memoryRepository.latestContext("working_memory") } }

                val rawFacts = factsDeferred.await()
                val rawPreferences = preferencesDeferred.await()
                val rawProjects = projectsDeferred.await().filter { it.status != "concluido" }
                val rawDecisions = decisionsDeferred.await()
                val rawPeople = peopleDeferred.await()
                val rawPlaces = placesDeferred.await()

                // observationCount — how many times each item actually informed a past response
                // (see MemoryRepository.accessCounts/schema's memory_access_counts). A soft ranking
                // signal, not core data: unlike the fetches above, a failure here (already swallowed
                // to an empty map inside accessCounts) just means this category ranks without the
                // signal this one time — not a reason to retry the whole digest.
                val factCounts = async { memoryRepository.accessCounts("memory_facts", rawFacts.mapNotNull { it.id }) }
                val prefCounts = async { memoryRepository.accessCounts("memory_preferences", rawPreferences.mapNotNull { it.id }) }
                val projectCounts = async { memoryRepository.accessCounts("memory_projects", rawProjects.mapNotNull { it.id }) }
                val decisionCounts = async { memoryRepository.accessCounts("memory_decisions", rawDecisions.mapNotNull { it.id }) }
                val peopleCounts = async { memoryRepository.accessCounts("memory_people", rawPeople.mapNotNull { it.id }) }
                val placeCounts = async { memoryRepository.accessCounts("memory_places", rawPlaces.mapNotNull { it.id }) }

                // Ranked by weightedScore (importance + confidence + real usage recency + real usage
                // count), not a single raw field — see MemoryRanking.kt. Tasks are the one exception:
                // deadline order (already how openTasks() sorts) is the criterion that actually
                // matters for "what's pending", not how recently/often a task was brought up.
                // Identity facts (nome, apelido, idioma...) are core to "who the user is" — never let
                // them get crowded out of the digest by unrelated facts competing for the top-5 ranked
                // slots below. Confirmed live: a user with several saved facts had their own name drop
                // out of what Claude saw, simply because it wasn't the most "recent"/"important" one.
                val facts = factCounts.await().let { counts ->
                    val (identity, semantic) = rawFacts.partition { it.tipo == "identity" }
                    val rankedSemantic = semantic.sortedByDescending { it.weightedScore(observationCount = counts[it.id] ?: 0) }.take(5)
                    identity.sortedByDescending { it.weightedScore(observationCount = counts[it.id] ?: 0) } + rankedSemantic
                }
                val preferences = prefCounts.await().let { counts -> rawPreferences.sortedByDescending { it.weightedScore(observationCount = counts[it.id] ?: 0) } }.take(5)
                val projects = projectCounts.await().let { counts -> rawProjects.sortedByDescending { it.weightedScore(observationCount = counts[it.id] ?: 0) } }.take(5)
                val decisions = decisionCounts.await().let { counts -> rawDecisions.sortedByDescending { it.weightedScore(observationCount = counts[it.id] ?: 0) } }.take(5)
                val people = peopleCounts.await().let { counts -> rawPeople.sortedByDescending { it.weightedScore(observationCount = counts[it.id] ?: 0) } }.take(5)
                val places = placeCounts.await().let { counts -> rawPlaces.sortedByDescending { it.weightedScore(observationCount = counts[it.id] ?: 0) } }.take(5)
                val tasks = tasksDeferred.await().take(5)
                // Only surface routines with real signal — RoutineInference.infer already requires 3+
                // occurrences, but a weak one (e.g. 3 times spread across two months) shouldn't be presented
                // to Claude as an established habit.
                val routines = routinesDeferred.await().filter { it.frequencia >= 0.25f }.take(3)
                val workingMemory = workingMemoryDeferred.await()
                val workingMemoryJson = workingMemory?.let { runCatching { JSONObject(it.valor) }.getOrNull() }

                if (facts.isEmpty() && preferences.isEmpty() && projects.isEmpty() && decisions.isEmpty() &&
                    people.isEmpty() && places.isEmpty() && tasks.isEmpty() && routines.isEmpty() && workingMemoryJson == null
                ) {
                    return@coroutineScope if (anyFailure) null else MemoryDigest(emptyList())
                }

                val entries = buildList {
                    facts.forEach { fact ->
                        val value = runCatching { JSONObject(fact.conteudo).optString("valor").ifBlank { fact.conteudo } }.getOrDefault(fact.conteudo)
                        // "identity", not "fact" — see MemoryDigest.forRequest, which always includes
                        // this category regardless of keyword matching, so the user's own name etc.
                        // can't get crowded out of a turn just because they didn't ask "quem sou eu".
                        val category = if (fact.tipo == "identity") "identity" else "fact"
                        add(MemoryEntry(category, fact.chave ?: fact.tipo, "- ${fact.chave ?: fact.tipo}: $value", "memory_facts", fact.id))
                    }
                    preferences.forEach { add(MemoryEntry("preference", "Preferência: ${it.assunto}", "- Prefere (${it.assunto}): ${it.preferencia}", "memory_preferences", it.id)) }
                    projects.forEach { project ->
                        val aliasNote = if (project.aliases.isNotEmpty()) " (também chamado de ${project.aliases.joinToString()})" else ""
                        val objective = project.objetivo?.let { " — objetivo: $it" } ?: ""
                        val next = project.proximaAcao?.let { " — próxima ação: $it" } ?: ""
                        val deadline = project.deadline?.let { " — prazo: $it" } ?: ""
                        add(MemoryEntry("project", project.nome, "- Projeto: ${project.nome}$aliasNote [${project.status}]$objective$next$deadline", "memory_projects", project.id))
                    }
                    decisions.forEach { add(MemoryEntry("decision", "Regra: ${it.regra}", "- Regra: ${it.regra}${it.periodo?.let { p -> " ($p)" } ?: ""}", "memory_decisions", it.id)) }
                    people.forEach { person ->
                        val related = person.assuntosAssociados.takeIf { it.isNotEmpty() }?.let { " — relacionado a: ${it.joinToString()}" } ?: ""
                        add(MemoryEntry("person", person.nome, "- Pessoa: ${person.nome}${person.relacao?.let { " ($it)" } ?: ""}$related", "memory_people", person.id))
                    }
                    places.forEach { place -> add(MemoryEntry("place", "Lugar: ${place.nome}", "- Lugar: ${place.nome}${place.endereco?.let { " ($it)" } ?: ""}", "memory_places", place.id)) }
                    tasks.forEach { add(MemoryEntry("task", "Tarefa: ${it.titulo}", "- Tarefa pendente: ${it.titulo}${it.prazo?.let { p -> " (prazo: $p)" } ?: ""} [${it.status}]", "memory_tasks", it.id)) }
                    routines.forEach { routine ->
                        val time = routine.horarioMedio?.take(5)?.let { " por volta das $it" } ?: ""
                        val days = formatRoutineDays(routine.dias).ifBlank { null }?.let { " ($it)" } ?: ""
                        add(MemoryEntry("routine", "Rotina: ${routineEventLabel(routine.evento)}", "- Rotina observada: costuma ${routineEventLabel(routine.evento)}$time$days", "memory_routines", routine.id))
                    }
                    workingMemoryJson?.let { wm ->
                        val objective = wm.optString("objetivo").ifBlank { null }
                        val next = wm.optString("proximo_passo").ifBlank { null }
                        if (objective != null) add(MemoryEntry("working", "Em andamento: $objective", "- Em andamento agora: $objective${next?.let { " — próximo passo: $it" } ?: ""}", "memory_context", workingMemory?.id))
                    }
                }
                MemoryDigest(entries)
            }

            if (digest != null || !anyFailure || attempt == MEMORY_DIGEST_ATTEMPTS - 1) return digest
            delay(1_500)
        }
        error("unreachable") // repeat() above always returns before exhausting its range
    }

    /** Runs a tool Claude decided to call, returning the plain-text result the model sees back —
     * writes here go through the exact same MemoryRepository methods MemoryScreen uses, tagged
     * "inferred"/unconfirmed (vs. "user_explicit"/confirmed for anything the user typed directly
     * into MemoryScreen) so a correction later knows this was Atlas's own guess, not a fact the
     * user stated to that screen themselves. */
    private suspend fun executeTool(name: String, input: JSONObject): String = toolExecutor.execute(name, input)

    /** Wires the two subscribers that used to be sequential inline calls at the end of
     * executeTool() — see [AgentEventBus]'s doc. Each `collect` is its own independent coroutine on
     * the same [AgentEventBus.events] flow: neither subscriber knows the other exists, matching the
     * plan's "nenhuma chamada direta entre agentes". Called once from init; a [SharedFlow] with no
     * replay only delivers events emitted after a collector starts, so this must run before the
     * first possible `executeTool()` call — guaranteed here since init{} blocks always finish before
     * any user action (sendMessage) can trigger a tool call. */
    private fun subscribeToAgentEvents() {
        viewModelScope.launch {
            agentEventBus.events.collect { event ->
                // Outcome Engine (plan section 12): a failed attempt is a real outcome too, not a
                // reason to skip logging — before this, only successes ever became an episode, so
                // "how often does X actually work" had no data for the failures half. See
                // [logEpisode]'s own doc for why it's kept at low importance instead of equal
                // footing with a success.
                if (event.toolName in ACTION_TOOLS) logEpisode(event.toolName, event.result, event.success)
            }
        }
        viewModelScope.launch {
            agentEventBus.events.collect { event ->
                routineEventKey(event.toolName, event.input, event.result)?.let { logRoutineEvent(it) }
            }
        }
    }

    /** Fire-and-forget episodic-memory write for a real device action — the architecture spec's
     * "o que aconteceu?" memory type (section 12) doubling as its Outcome Model, since a
     * [MemoryEpisode]'s own `resultado` already carries the same "Não consegui..." prefix
     * convention [PersistedAction.failed] reads success/failure from elsewhere in this codebase —
     * no separate `success` column needed to ask "did this kind of action usually work?" later.
     * Deliberately never blocks or fails the tool call itself: a failed episode *write* shouldn't
     * turn a real action (whatever its own outcome was) into an error the user sees. Only fires for
     * [ACTION_TOOLS] — memory writes (save_fact etc.) are already their own kind of memory and
     * logging "Atlas remembered something" as an episode of "something happening" would blur the
     * two concepts the spec keeps separate. */
    private fun logEpisode(toolName: String, result: String, success: Boolean) {
        viewModelScope.launch {
            runCatching {
                val evento = ACTION_TOOL_LABELS[toolName] ?: toolName
                // Policy Engine (plan section 17): a failed high-impact action (a WhatsApp message
                // that didn't actually send) is worth remembering more than a failed low-impact one
                // (brightness) — see HIGH_IMPACT_TOOLS' own doc for why this stops at importance
                // weighting rather than a confirmation gate.
                val highImpact = toolName in HIGH_IMPACT_TOOLS
                val episode = memoryRepository.addEpisode(
                    MemoryEpisode(
                        evento = evento,
                        resultado = result,
                        origem = "observed",
                        confidence = 1f,
                        // A failure is real signal (the Outcome Model's whole point) but shouldn't
                        // compete with successes for one of buildMemoryDigest's 5-per-category
                        // slots — kept low enough to stay out of the way there while still existing
                        // for anything that later wants a real success-rate-per-tool signal.
                        importance = when {
                            success && highImpact -> 0.5f
                            success -> 0.4f
                            highImpact -> 0.3f
                            else -> 0.15f
                        },
                    ),
                )
                // Fire-and-forget hook retained for the local search index. Never awaited for its
                // own sake — indexing must not hold up the
                // action this episode is just a record of.
                launch { runCatching { memoryRepository.indexForSearch("memory_episodes", episode.id, "$evento: $result") } }
            }
        }
    }

    /** Fire-and-forget: logs one occurrence of a habit-shaped tool call and re-infers its routine
     * (see [MemoryRepository.logEventAndInferRoutine]). Same never-block-the-caller discipline as
     * [logEpisode] — a routine is a nice-to-have inference, never something a hiccup here should
     * turn into a user-visible error on the action that triggered it. */
    private fun logRoutineEvent(eventKey: String) {
        viewModelScope.launch {
            runCatching { memoryRepository.logEventAndInferRoutine(eventKey) }
        }
    }

    /** Procedural Learning (plan section 14), the data-collection-only slice: when one user turn
     * triggers 2+ tool calls, the ORDER they ran in is itself a signal ("toda vez que cria uma
     * tarefa, também define um lembrete") — but there's no real usage history yet to mine that
     * signal against, so this only logs the raw sequence into the existing memory_events pipeline
     * (same table/shape [logRoutineEvent] already writes to) rather than building pattern-detection
     * logic against data that doesn't exist yet. Fire-and-forget, same discipline as [logEpisode]. */
    private fun logToolSequence(toolNames: List<String>) {
        viewModelScope.launch {
            runCatching {
                memoryRepository.logEvent(
                    MemoryEvent(
                        evento = "sequencia_tools",
                        payload = JSONArray(toolNames).toString(),
                        importance = 0.1f,
                    ),
                )
            }
        }
    }

    /** Fire-and-forget: marks every item in [items] as used in [tabelaOrigem] (see
     * [MemoryRepository.markUsed]) without delaying whatever just finished building the digest that
     * included them. */
    private fun markUsedAsync(tabelaOrigem: String, items: List<MemoryMeta>) {
        val ids = items.mapNotNull { it.id }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { id -> runCatching { memoryRepository.markUsed(tabelaOrigem, id) } }
        }
    }

    private fun markSelectedMemoryAsync(usage: List<Pair<String, List<String>>>) {
        if (usage.isEmpty()) return
        viewModelScope.launch {
            usage.forEach { (table, ids) -> ids.forEach { id -> runCatching { memoryRepository.markUsed(table, id) } } }
        }
    }

    fun signIn() {
        if (authBusy) return
        authBusy = true
        authError = null
        viewModelScope.launch {
            authRepository.signIn(email.trim(), password)
                .onFailure { authError = it.message ?: "Falha ao entrar" }
            if (authRepository.state.value is AuthState.MfaRequired) {
                password = ""
                mfaCode = ""
            }
            authBusy = false
        }
    }

    fun updateMfaCode(value: String) {
        mfaCode = value.filter(Char::isDigit).take(6)
        authError = null
    }

    fun verifyMfaCode() {
        if (authBusy) return
        authBusy = true
        authError = null
        viewModelScope.launch {
            authRepository.verifyPendingMfa(mfaCode)
                .onFailure { authError = "Código incorreto ou expirado. Confira o app autenticador e tente novamente." }
            if (authRepository.state.value is AuthState.LoggedIn) mfaCode = ""
            authBusy = false
        }
    }

    fun cancelMfaSignIn() {
        if (authBusy) return
        authBusy = true
        authError = null
        viewModelScope.launch {
            authRepository.cancelPendingMfa()
            mfaCode = ""
            password = ""
            authBusy = false
        }
    }

    fun signUp() {
        if (authBusy) return
        authBusy = true
        authError = null
        viewModelScope.launch {
            authRepository.signUp(email.trim(), password)
                .onFailure { authError = it.message ?: "Falha ao criar conta" }
            authBusy = false
        }
    }

    /** FUTURE: [idToken] comes from Credential Manager when Google login is enabled in the UI. */
    fun signInWithGoogle(idToken: String) {
        if (authBusy) return
        authBusy = true
        authError = null
        viewModelScope.launch {
            authRepository.signInWithGoogle(idToken)
                .onFailure { authError = it.message ?: "Falha ao entrar com Google" }
            authBusy = false
        }
    }

    /** Called by the Activity that receives the `atlas://auth-callback` deep link. */
    fun handleAuthCallback(fragment: String) {
        viewModelScope.launch {
            authRepository.completeEmailConfirmation(fragment)
                .onFailure { authError = it.message ?: "Não foi possível confirmar o e-mail" }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
            messages.clear()
            conversations.clear()
            currentConversationId = ""
            historyStore.clearAll()
            memoryContext = null
        }
    }

    fun sendMessage() {
        val text = inputText.trim()
        val image = pendingImageBase64
        val pdf = pendingPdfBase64
        val pdfName = pendingPdfFileName
        if ((text.isBlank() && image == null && pdf == null) || isSending) return
        if (voiceReviewPending) pendingVoiceReply = true
        voiceReviewPending = false
        if (currentConversationId.isBlank()) {
            // Self-healing fallback (e.g. right after sign-out/sign-in in the same session, where
            // no conversation has been created yet) — normally init{} already guarantees one exists.
            val summary = historyStore.createConversation()
            refreshConversations()
            switchConversation(summary.id)
        }
        // A photo/PDF with no caption still needs *some* non-empty text block for the API — this
        // placeholder is honest about what the user actually sent (no words, just the attachment).
        val messageText = text.ifBlank { if (pdf != null) "(documento anexado)" else "(imagem anexada)" }
        inputText = ""
        pendingImageBase64 = null
        pendingPdfBase64 = null
        pendingPdfFileName = null
        chatError = null
        val isFirstMessage = messages.isEmpty()
        messages.add(ChatMessage(role = "user", content = messageText, imageBase64 = image, pdfBase64 = pdf, pdfFileName = pdfName))
        if (isFirstMessage) {
            // Auto-title from the opening message, like most chat apps — a no-op if the user
            // already renamed this conversation before sending anything (see autoTitleIfUntitled).
            historyStore.autoTitleIfUntitled(
                currentConversationId,
                messageText.take(40).let { if (messageText.length > 40) "$it…" else it },
            )
            refreshConversations()
        }
        dispatchSend(messageText)
    }

    /** Re-sends the last turn after a failed request (network blip, API hiccup) — the user's
     * message already landed in [messages]/[historyStore] when the original attempt set
     * [chatError], so this must NOT append another copy of it; see the "Tentar novamente" banner
     * action in ChatScreen wired to this. A no-op if the last message isn't an unanswered user
     * turn (defends against a stale button if something else changed state in between). */
    fun retryLastMessage() {
        if (isSending) return
        val last = messages.lastOrNull() ?: return
        if (last.role != "user") return
        chatError = null
        dispatchSend(last.content)
    }

    private fun dispatchSend(messageText: String) {
        isSending = true
        executingTool = null
        streamingText = null
        val conversationId = currentConversationId
        val history = messages.toList()
        viewModelScope.launch {
            try {
                historyStore.saveMessages(conversationId, history)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                chatError = failure.message ?: "Não foi possível salvar a conversa neste dispositivo."
                isSending = false
                return@launch
            }
            // Clear, unambiguous device commands ("abre o whatsapp", "volume 20%") skip the AI
            // entirely — faster, free, and resilient to both chat backends being down at once (the
            // real gap a user hit: an Anthropic billing outage took down even "abaixa o volume").
            // Never attempted with an attached image — vision needs a real model. Anything that
            // doesn't match this narrowly falls straight through to the normal flow below, unchanged.
            val lastHasAttachment = history.lastOrNull()?.let { it.imageBase64 != null || it.pdfBase64 != null } ?: false
            if (!lastHasAttachment) {
                // The turn right before the new user message, if any — lets a bare follow-up like
                // "coloca em 15" inherit "volume" from the reply just above it (see
                // LocalCommandRouter's own doc on exactly why and how narrowly this is trusted).
                val lastAssistantReply = history.dropLast(1).lastOrNull()?.takeIf { it.role == "assistant" }?.content
                val local = try {
                    localCommandRouter.tryHandle(messageText, lastAssistantReply)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (local != null) {
                    val assistantMessage = ChatMessage(role = "assistant", content = local.resultText, modelText = local.resultText)
                    if (currentConversationId == conversationId) messages.add(assistantMessage)
                    try {
                        historyStore.saveMessages(conversationId, history + assistantMessage)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        if (currentConversationId == conversationId) {
                            chatError = "A ação foi executada, mas a conversa não pôde ser salva neste dispositivo."
                        }
                    }
                    isSending = false
                    speakReplyIfVoiceOriginated(local.resultText)
                    return@launch
                }
            }
            // A missing digest means the initial load failed or is still running. Retry it in the
            // background, but never hold the model request open for Supabase memory reads.
            loadMemoryDigest()
            // Snapshot before building this request's prompt; later refreshes only affect later turns.
            val currentDigest = memoryContext
            // This is a fresh local relevance search conditioned on this request. It only runs for
            // explicit memory/history questions, and failure never blocks the chat turn.
            val selectedMemory = currentDigest?.forRequest(messageText)
            val semanticEpisodes = if (shouldRetrieveSemanticEpisodes(messageText)) {
                runCatching { memoryRepository.semanticEpisodes(messageText, limit = 3) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val usedMemory = selectedMemory != null || semanticEpisodes.isNotEmpty()
            val systemPrompt = buildString {
                append(DEFAULT_SYSTEM_PROMPT)
                append("\n\nSe o usuário corrigir a intenção ou o público do pedido, aceite a correção de forma direta, sem ironia, brincadeira ou comentário sobre a própria identidade. Ao preparar uma apresentação, biografia ou texto para compartilhar, use apenas dados de marca e informações fornecidas explicitamente para esse texto; não inclua nome, projetos, contatos, localização ou outras informações pessoais da memória. Se perguntarem sobre recursos do app ou pedirem uma automação incompatível, consulte a ferramenta de capacidades e explique com precisão o que está disponível e quais limites do Android se aplicam.")
                append("\n\n")
                append(currentDateTimeContext())
                append("\n")
                append(currentDeviceContext(appContext))
                selectedMemory?.let { append("\n\n"); append(it.promptText) }
                if (semanticEpisodes.isNotEmpty()) {
                    append("\n\nLembranças relevantes a esta pergunta (algo parecido já aconteceu antes):")
                    semanticEpisodes.forEach { append("\n- ${it.evento}: ${it.resultado.orEmpty()}") }
                }
                if (!ChatRequestPolicy.isPublicFacing(messageText)) {
                    interactionStyleHint(history)?.let { append("\n\n"); append(it) }
                }
            }
            lastSystemPromptSent = systemPrompt
            val result = runCatching {
                chatClient.send(
                    history = history.withoutPreviousPdfAttachments(),
                    accessToken = authRepository.currentAccessToken()
                        ?: error("Entre na sua conta para conversar com o Atlas."),
                    systemPrompt = systemPrompt,
                    tools = ATLAS_TOOLS,
                    onToolCall = ::executeTool,
                    onActivity = { toolName ->
                        if (currentConversationId == conversationId) executingTool = toolName
                    },
                    onTextUpdate = { text ->
                        if (currentConversationId == conversationId) streamingText = text
                    },
                )
            }

            result
                .onSuccess { reply ->
                    // No more appended "✓ ..." text line — actions are now structured data the UI
                    // renders as its own Action Card / memory caption (see ChatScreen.kt), instead
                    // of a hardcoded string baked into the message body.
                    val actions = reply.actions.map {
                        PersistedAction(it.toolName, it.resultSummary, failed = it.resultSummary.startsWith("Não consegui"))
                    }
                    val assistantMessage = ChatMessage(
                        role = "assistant",
                        content = reply.text,
                        usedMemory = usedMemory,
                        modelText = reply.text,
                        actions = actions,
                        memoryLabels = (selectedMemory?.labels ?: emptyList()) + semanticEpisodes.map { "Lembrança: ${it.evento}" },
                        answeredByFallback = reply.answeredByFallback,
                    )
                    if (currentConversationId == conversationId) messages.add(assistantMessage)
                    try {
                        historyStore.saveMessages(conversationId, history + assistantMessage)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (currentConversationId == conversationId) {
                            chatError = "A resposta chegou, mas a conversa não pôde ser salva neste dispositivo."
                        }
                    }
                    selectedMemory?.let { markSelectedMemoryAsync(it.usage) }
                    markUsedAsync("memory_episodes", semanticEpisodes)
                    if (reply.actions.isNotEmpty()) refreshMemoryContext()
                    if (reply.actions.size >= 2) logToolSequence(reply.actions.map { it.toolName })
                    speakReplyIfVoiceOriginated(reply.text)
                }
                .onFailure {
                    val message = it.message ?: "Falha ao falar com o Claude"
                    if (currentConversationId == conversationId) chatError = message
                    pendingVoiceReply = false
                }
            isSending = false
            executingTool = null
            streamingText = null
        }
    }

    private fun List<ChatMessage>.withoutPreviousPdfAttachments(): List<ChatMessage> {
        val currentPdfIndex = lastIndex.takeIf { index ->
            getOrNull(index)?.let {
                it.role == "user" && it.pdfBase64?.let { encoded -> !pdfExceedsRequestLimit(encoded) } == true
            } == true
        }
        return mapIndexed { index, message ->
            val pdfBase64 = message.pdfBase64
            if (pdfBase64 != null && index != currentPdfIndex) {
                val note = if (pdfExceedsRequestLimit(pdfBase64)) {
                    "[PDF excede o limite atual de 10 MiB e não foi enviado. Selecione um arquivo menor para analisá-lo.]"
                } else {
                    "[PDF anterior não foi reenviado. Anexe-o novamente para uma nova análise.]"
                }
                message.copy(
                    content = "${message.content}\n$note",
                    pdfBase64 = null,
                    pdfFileName = null,
                )
            } else {
                message
            }
        }
    }

    private fun pdfExceedsRequestLimit(encoded: String): Boolean {
        val padding = encoded.takeLastWhile { it == '=' }.length
        val decodedBytes = encoded.length.toLong() * 3 / 4 - padding
        return decodedBytes > MAX_PDF_BYTES
    }

    /** Interaction Learning (plan section 24), the cheapest real version of it: instead of only
     * acting on an explicitly stated preference ("respostas curtas", via save_preference), read the
     * user's own recent messages for a style signal that's already sitting right there. Computed
     * fresh from the visible conversation every turn — no new storage, no decay logic needed, and
     * it naturally follows the conversation's own character as it changes. Needs at least 4 of the
     * user's own messages before guessing anything; returns null (no hint added) below that and in
     * the ambiguous middle band, rather than forcing a style call on thin evidence. */
    private fun interactionStyleHint(history: List<ChatMessage>): String? {
        val recentUserMessages = history.filter { it.role == "user" }.takeLast(10)
        if (recentUserMessages.size < 4) return null
        val avgLength = recentUserMessages.map { it.content.length }.average()
        return when {
            avgLength < 25 -> "O usuário costuma escrever mensagens curtas e diretas — prefira respostas igualmente curtas e objetivas, sem rodeios."
            avgLength > 120 -> "O usuário costuma escrever mensagens longas e detalhadas — pode responder com mais detalhe e contexto quando fizer sentido."
            else -> null
        }
    }

    private fun shouldRetrieveSemanticEpisodes(text: String): Boolean {
        if (ChatRequestPolicy.isPublicFacing(text)) return false
        val query = ChatRequestPolicy.normalize(text)
        return ChatRequestPolicy.isExplicitMemoryRecall(text) || listOf(
            "da outra vez", "ultima vez", "ja aconteceu", "ja fizemos", "como fizemos",
            "o que voce fez", "lembra quando", "historico da conversa",
        ).any(query::contains)
    }

    /** Speaks [text] aloud and (in hands-free mode) re-arms the mic afterward — the same behavior
     * a Claude/Gemini reply already had, extracted so a locally-handled device command (see
     * [LocalCommandRouter]) gets the identical spoken confirmation when it was voice-originated,
     * instead of silently skipping it. No-op (and does NOT clear [pendingVoiceReply]'s flag,
     * callers must still do that themselves) unless this turn actually came in by voice. */
    private fun speakReplyIfVoiceOriginated(text: String) {
        if (!pendingVoiceReply) return
        pendingVoiceReply = false
        if (!voiceSettings.speakReplies || !voiceScreenActive) return
        isSpeaking = true
        voiceController.speak(
            text, voiceSettings.speechRate,
            voiceName = voiceSettings.speechVoiceName,
            // Barge-in only actually arms with a headset connected (see
            // VoiceController.isHeadsetConnected) — always on here is safe, it's simply inert on
            // the speaker where it can't be told apart from Atlas's own voice.
            allowBargeIn = true,
            onBargeIn = { isSpeaking = false; startVoiceInput() },
            onError = { message -> isSpeaking = false; voiceError = message },
            onCancelled = { isSpeaking = false },
        ) {
            isSpeaking = false
            handsFreeRestart?.cancel()
            handsFreeRestart = viewModelScope.launch {
                // Let the speaker finish before rearming the microphone — a headset has no
                // speaker-to-mic leakage to guard against, so it only needs enough delay to avoid
                // feeling instantaneous.
                kotlinx.coroutines.delay(if (voiceController.isHeadsetConnected()) 150L else 450L)
                if (voiceScreenActive && voiceSettings.handsFreeMode &&
                    voiceSettings.speakReplies && !isSending && !isListening && !isSpeaking) {
                    startVoiceInput()
                }
            }
        }
    }
}
