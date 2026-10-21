package com.unyx.atlas.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unyx.atlas.memory.MemoryEpisode
import com.unyx.atlas.memory.MemoryEvent
import com.unyx.atlas.memory.MemoryRepository
import com.unyx.atlas.memory.MemorySemanticMatch
import com.unyx.atlas.memory.athena.AthenaGateway
import com.unyx.atlas.memory.athena.WorldQuery
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One [MemorySemanticMatch] resolved to something readable — the episode text it points at, when
 * the match is a `memory_episodes` row (the only kind actually indexed today; see
 * [ChatViewModel.logEpisode]). Any other [tabelaOrigem] just shows its raw id, since there's
 * nothing yet resolving those. */
data class ResolvedSemanticMatch(
    val tabelaOrigem: String,
    val registroId: String,
    val similarity: Float,
    val label: String,
)

/**
 * Backs the Debug screen (architecture spec section 37: "durante desenvolvimento, criar tela
 * interna para inspecionar..."). Deliberately the opposite editorial stance of [MemoryScreen]/
 * [ContextScreen] (which show clean, human-facing sentences) — this one is allowed to show raw
 * local relevance scores and table names, because its whole purpose is confirming what the
 * retrieval pipeline actually did, not presenting a friendly summary of it.
 */
@HiltViewModel
class DebugViewModel @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val athenaGateway: AthenaGateway,
) : ViewModel() {

    // M1 mock only (see athena-query's own doc) — status is expected to read UNSUPPORTED until a
    // real provider lands. This just proves ATLAS can reach ATHENA end to end from the device.
    var athenaIntent by mutableStateOf("CURRENT_WEATHER")
    var athenaResult by mutableStateOf<String?>(null)
    var athenaError by mutableStateOf<String?>(null)
    var athenaBusy by mutableStateOf(false)
        private set

    fun testAthenaQuery() {
        viewModelScope.launch {
            athenaBusy = true
            athenaError = null
            athenaResult = null
            runCatching { athenaGateway.query(WorldQuery(intent = athenaIntent)) }
                .onSuccess { athenaResult = it.raw }
                .onFailure { athenaError = "${it::class.simpleName}: ${it.message}" }
            athenaBusy = false
        }
    }

    var searchQuery by mutableStateOf("")
    var isSearching by mutableStateOf(false)
        private set
    val searchResults = mutableStateListOf<ResolvedSemanticMatch>()
    var searchError by mutableStateOf<String?>(null)

    val recentEvents = mutableStateListOf<MemoryEvent>()
    var isLoading by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)

    fun reload() {
        viewModelScope.launch {
            isLoading = true
            loadError = null
            runCatching {
                val events = memoryRepository.recentEvents(limit = 30)
                recentEvents.clear()
                recentEvents.addAll(events)
            }.onFailure { loadError = it.message ?: "Falha ao carregar" }
            isLoading = false
        }
    }

    /** Runs [searchQuery] through the same [MemoryRepository.semanticSearch] real chat traffic
     * uses — this is the whole point of the screen: seeing exactly what a query would retrieve and
     * with what local relevance score, instead of only inferring it indirectly from a chat reply. */
    fun runSearch() {
        val query = searchQuery.trim()
        if (query.isBlank()) return
        viewModelScope.launch {
            isSearching = true
            searchError = null
            runCatching {
                val matches = memoryRepository.semanticSearch(query, limit = 8)
                resolveMatches(matches)
            }.onSuccess {
                searchResults.clear(); searchResults.addAll(it)
            }.onFailure {
                searchError = it.message ?: "Falha na busca local"
            }
            isSearching = false
        }
    }

    private suspend fun resolveMatches(matches: List<MemorySemanticMatch>): List<ResolvedSemanticMatch> = coroutineScope {
        val episodeIds = matches.filter { it.tabelaOrigem == "memory_episodes" }.map { it.registroId }
        val episodesById: Map<String?, MemoryEpisode> = if (episodeIds.isEmpty()) {
            emptyMap()
        } else {
            memoryRepository.episodesByIds(episodeIds).associateBy { it.id }
        }
        matches.map { match ->
            val label = episodesById[match.registroId]?.let { "${it.evento}: ${it.resultado.orEmpty()}" }
                ?: "${match.tabelaOrigem} · ${match.registroId.take(8)}…"
            ResolvedSemanticMatch(match.tabelaOrigem, match.registroId, match.similarity, label)
        }
    }
}
