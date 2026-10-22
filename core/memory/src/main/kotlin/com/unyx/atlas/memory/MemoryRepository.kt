package com.unyx.atlas.memory

import com.unyx.atlas.memory.auth.AuthRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * The one entry point the rest of the app (chat, and later voice/agents) talks to for reading and
 * writing Atlas's memory — wraps [EncryptedMemoryStore] with typed methods per memory kind, plus the
 * handful of behaviors the design calls for that are more than plain CRUD (corrections
 * soft-invalidating what they supersede, project alias lookup, etc.).
 *
 * Every call resolves "who is this" from [auth] rather than taking a userId parameter. Both
 * Supabase RLS and the encrypted envelope are bound to the authenticated account, so there is no
 * meaningful way to act as anyone else — fail clearly with [NotSignedInException] instead.
 */
class MemoryRepository(
    private val secureStore: EncryptedMemoryStore,
    private val auth: AuthRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val isConfigured: Boolean get() = secureStore.isConfigured

    class NotSignedInException : IllegalStateException("No signed-in Atlas user — sign in before touching memory")

    private suspend fun requireSession(): Pair<String, String> {
        val token = auth.currentAccessToken() ?: throw NotSignedInException()
        val userId = auth.currentUserId() ?: throw NotSignedInException()
        return token to userId
    }

    /**
     * Everything stored about the signed-in user, decrypted on this device, grouped by memory table
     * — the data-portability copy the LGPD (art. 18) asks for. Embeddings are left out: they are
     * derived vectors, not something the person wrote or can read. Nothing is sent anywhere; the
     * caller decides where the file goes.
     */
    suspend fun exportAll(): JSONObject = withContext(ioDispatcher) {
        val (token, userId) = requireSession()
        val tables = JSONObject()
        secureStore.allRows(token)
            .filter { (table, _) -> table != "memory_embeddings" }
            .forEach { (table, row) ->
                val rows = tables.optJSONArray(table) ?: JSONArray().also { tables.put(table, it) }
                rows.put(row)
            }
        JSONObject()
            .put("exportado_em", Instant.now().toString())
            .put("conta", userId)
            .put("observacao", "Cópia dos dados que o Atlas guarda sobre você, decifrados neste aparelho.")
            .put("tabelas", tables)
    }

    // ---- Facts ----

    suspend fun addFact(fact: MemoryFact): MemoryFact = withContext(ioDispatcher) {
        val (token, userId) = requireSession()
        secureStore.insert("memory_facts", fact.copy(userId = userId).toInsertJson(), token).toMemoryFact()
    }

    suspend fun facts(tipo: String? = null): List<MemoryFact> = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        val filter = buildString {
            append("valid=eq.true")
            if (tipo != null) append("&tipo=eq.${tipo.urlEncode()}")
            append("&order=importance.desc")
        }
        secureStore.select("memory_facts", filter, token).map { it.toMemoryFact() }
    }

    /** Case-insensitive lookup by [chave] — lets a new `save_fact` call correct an existing one
     * instead of silently inserting a second, contradictory row for the same key (see
     * [findPersonByName], the same pattern already fixed a real duplicate-row bug for people). */
    suspend fun findFactByChave(chave: String): MemoryFact? = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.select("memory_facts", "valid=eq.true&chave=ilike.${chave.urlEncode()}", token)
            .map { it.toMemoryFact() }
            .firstOrNull()
    }

    // ---- Preferences ----

    suspend fun addPreference(preference: MemoryPreference): MemoryPreference = withContext(ioDispatcher) {
        val (token, userId) = requireSession()
        secureStore.insert("memory_preferences", preference.copy(userId = userId).toInsertJson(), token).toMemoryPreference()
    }

    suspend fun preferences(assunto: String? = null): List<MemoryPreference> = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        val filter = buildString {
            append("valid=eq.true")
            if (assunto != null) append("&assunto=eq.${assunto.urlEncode()}")
            append("&order=confidence.desc")
        }
        secureStore.select("memory_preferences", filter, token).map { it.toMemoryPreference() }
    }

    /** Case-insensitive lookup by [assunto] — same duplicate-avoidance purpose as [findFactByChave]. */
    suspend fun findPreferenceByAssunto(assunto: String): MemoryPreference? = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.select("memory_preferences", "valid=eq.true&assunto=ilike.${assunto.urlEncode()}", token)
            .map { it.toMemoryPreference() }
            .firstOrNull()
    }

    // ---- Events (raw, high-volume — feeds routine inference) ----

    suspend fun logEvent(event: MemoryEvent): MemoryEvent = withContext(ioDispatcher) {
        val (token, userId) = requireSession()
        secureStore.insert("memory_events", event.copy(userId = userId).toInsertJson(), token).toMemoryEvent()
    }

    suspend fun recentEvents(limit: Int = 100): List<MemoryEvent> = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.select("memory_events", "order=created_at.desc&limit=$limit", token).map { it.toMemoryEvent() }
    }

    /** All occurrences of one `evento` key — the raw material [RoutineInference.infer] aggregates. */
    suspend fun eventsByType(evento: String, limit: Int = 500): List<MemoryEvent> = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.select("memory_events", "evento=eq.${evento.urlEncode()}&order=created_at.desc&limit=$limit", token)
            .map { it.toMemoryEvent() }
    }

    // ---- Episodes ----

    suspend fun addEpisode(episode: MemoryEpisode): MemoryEpisode = withContext(ioDispatcher) {
        val (token, userId) = requireSession()
        secureStore.insert("memory_episodes", episode.copy(userId = userId).toInsertJson(), token).toMemoryEpisode()
    }

    suspend fun recentEpisodes(limit: Int = 20): List<MemoryEpisode> = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.select("memory_episodes", "valid=eq.true&order=data.desc&limit=$limit", token).map { it.toMemoryEpisode() }
    }

    // ---- Routines ----

    suspend fun addRoutine(routine: MemoryRoutine): MemoryRoutine = withContext(ioDispatcher) {
        val (token, userId) = requireSession()
        secureStore.insert("memory_routines", routine.copy(userId = userId).toInsertJson(), token).toMemoryRoutine()
    }

    suspend fun routines(): List<MemoryRoutine> = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.select("memory_routines", "valid=eq.true&order=frequencia.desc", token).map { it.toMemoryRoutine() }
    }

    suspend fun findRoutineByEvento(evento: String): MemoryRoutine? = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.select("memory_routines", "valid=eq.true&evento=eq.${evento.urlEncode()}", token)
            .map { it.toMemoryRoutine() }
            .firstOrNull()
    }

    suspend fun updateRoutine(id: String, fields: JSONObject) = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.update("memory_routines", id, fields, token)
    }

    /**
     * Logs one occurrence of [evento] and re-infers its routine from the event's full history —
     * cheap enough to do inline on every call (bounded by [eventsByType]'s limit) and keeps
     * memory_routines current without a separate scheduled job. Fire-and-forget, same discipline as
     * [logAccess]: a failed/incomplete inference should never surface as an error to whatever real
     * action triggered the event log (e.g. `open_app` succeeding shouldn't fail just because routine
     * bookkeeping hiccuped). Silently does nothing if there still isn't enough signal (see
     * [RoutineInference.infer]) — a routine only appears once it's real, and an already-established
     * one is never downgraded by one recent gap in usage.
     */
    suspend fun logEventAndInferRoutine(evento: String, payload: String? = null) {
        runCatching {
            logEvent(MemoryEvent(evento = evento, payload = payload, importance = 0.1f))
            val events = eventsByType(evento)
            val inferred = RoutineInference.infer(evento, events) ?: return@runCatching
            val existing = findRoutineByEvento(evento)
            val existingId = existing?.id
            if (existingId != null) {
                updateRoutine(
                    existingId,
                    JSONObject().apply {
                        put("horario_medio", inferred.horarioMedio)
                        put("frequencia", inferred.frequencia)
                        put("dias", org.json.JSONArray(inferred.dias))
                        put("confidence", inferred.confidence)
                        put("importance", inferred.importance)
                    },
                )
            } else {
                addRoutine(inferred)
            }
        }
    }

    // ---- People ----

    // ---- Places ----

    suspend fun addPlace(place: MemoryPlace): MemoryPlace = withContext(ioDispatcher) {
        val (token, userId) = requireSession()
        secureStore.insert("memory_places", place.copy(userId = userId).toInsertJson(), token).toMemoryPlace()
    }

    suspend fun places(): List<MemoryPlace> = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.select("memory_places", "valid=eq.true&order=importance.desc", token).map { it.toMemoryPlace() }
    }

    /** Case-insensitive lookup so a second mention of the same place corrects it instead of
     * duplicating it — same pattern as [findPersonByName]/[findProjectByName]. */
    suspend fun findPlaceByName(nome: String): MemoryPlace? = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.select("memory_places", "valid=eq.true&nome=ilike.${nome.urlEncode()}", token)
            .map { it.toMemoryPlace() }
            .firstOrNull()
    }

    suspend fun updatePlace(id: String, fields: JSONObject) = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.update("memory_places", id, fields, token)
    }

    // ---- People ----

    suspend fun addPerson(person: MemoryPerson): MemoryPerson = withContext(ioDispatcher) {
        val (token, userId) = requireSession()
        secureStore.insert("memory_people", person.copy(userId = userId).toInsertJson(), token).toMemoryPerson()
    }

    suspend fun people(): List<MemoryPerson> = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.select("memory_people", "valid=eq.true&order=importance.desc", token).map { it.toMemoryPerson() }
    }

    /** Case-insensitive lookup so "joão" and "João" resolve to the same person. */
    suspend fun findPersonByName(nome: String): MemoryPerson? = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.select("memory_people", "valid=eq.true&nome=ilike.${nome.urlEncode()}", token)
            .map { it.toMemoryPerson() }
            .firstOrNull()
    }

    /** Reverse lookup: people whose [MemoryPerson.assuntosAssociados] includes [tag] — the read side
     * of the lightweight relationship graph `link_person` writes (person ↔ project/person/topic
     * tags via that one free-text array, rather than a dedicated graph table this single-user store
     * doesn't need). Same PostgREST array-contains filter [findProjectByAlias] already uses. */
    suspend fun peopleAssociatedWith(tag: String): List<MemoryPerson> = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.select("memory_people", "valid=eq.true&assuntos_associados=cs.%7B${tag.urlEncode()}%7D", token)
            .map { it.toMemoryPerson() }
    }

    // ---- Projects ----

    suspend fun addProject(project: MemoryProject): MemoryProject = withContext(ioDispatcher) {
        val (token, userId) = requireSession()
        secureStore.insert("memory_projects", project.copy(userId = userId).toInsertJson(), token).toMemoryProject()
    }

    suspend fun projects(): List<MemoryProject> = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.select("memory_projects", "valid=eq.true&order=importance.desc", token).map { it.toMemoryProject() }
    }

    /** Resolves an ambiguous mention ("o launcher") to whichever project lists it as an alias. */
    suspend fun findProjectByAlias(alias: String): MemoryProject? = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        // PostgREST array-contains: aliases=cs.{value}
        secureStore.select("memory_projects", "valid=eq.true&aliases=cs.%7B${alias.urlEncode()}%7D", token)
            .map { it.toMemoryProject() }
            .firstOrNull()
    }

    suspend fun findProjectByName(nome: String): MemoryProject? = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.select("memory_projects", "valid=eq.true&nome=ilike.${nome.urlEncode()}", token)
            .map { it.toMemoryProject() }
            .firstOrNull()
    }

    /** Patches just [fields] on an existing project — used by the `update_project` tool to record
     * a new `status`/`objetivo`/`proxima_acao` without touching the rest of the row (spec section
     * 17's "continuidade de projetos": knowing the next step is what lets "vamos continuar aquele
     * projeto" get a real answer instead of "continuar o quê?"). */
    suspend fun updateProject(id: String, fields: JSONObject) = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.update("memory_projects", id, fields, token)
    }

    // ---- Tasks ----

    suspend fun addTask(task: MemoryTask): MemoryTask = withContext(ioDispatcher) {
        val (token, userId) = requireSession()
        secureStore.insert("memory_tasks", task.copy(userId = userId).toInsertJson(), token).toMemoryTask()
    }

    /** Open tasks (anything not completed/cancelled), soonest deadline first — nulls (no deadline)
     * sort last since PostgREST's default null ordering is "nulls last" for ascending order. */
    suspend fun openTasks(): List<MemoryTask> = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.select("memory_tasks", "valid=eq.true&status=in.(pending,in_progress,blocked)&order=prazo.asc", token)
            .map { it.toMemoryTask() }
    }

    suspend fun findTaskByTitle(titulo: String): MemoryTask? = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.select("memory_tasks", "valid=eq.true&titulo=ilike.${titulo.urlEncode()}", token)
            .map { it.toMemoryTask() }
            .firstOrNull()
    }

    suspend fun updateTask(id: String, fields: JSONObject) = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.update("memory_tasks", id, fields, token)
    }

    // ---- Decisions ----

    suspend fun addDecision(decision: MemoryDecision): MemoryDecision = withContext(ioDispatcher) {
        val (token, userId) = requireSession()
        secureStore.insert("memory_decisions", decision.copy(userId = userId).toInsertJson(), token).toMemoryDecision()
    }

    suspend fun decisions(): List<MemoryDecision> = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.select("memory_decisions", "valid=eq.true&order=importance.desc", token).map { it.toMemoryDecision() }
    }

    /** Case-insensitive lookup by [regra] — same duplicate-avoidance purpose as [findFactByChave].
     * Weaker signal than the others (a rule's exact wording varies more turn to turn than a fact's
     * key or a preference's subject), but still better than never checking at all. */
    suspend fun findDecisionByRegra(regra: String): MemoryDecision? = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.select("memory_decisions", "valid=eq.true&regra=ilike.${regra.urlEncode()}", token)
            .map { it.toMemoryDecision() }
            .firstOrNull()
    }

    // ---- Corrections ----

    /**
     * Records that [registroId] in [tabelaOrigem] was wrong, soft-invalidates that row (never
     * deletes it), and logs why — so "what changed and when" stays legible instead of the old
     * value silently vanishing or a second, contradictory record sitting next to it.
     */
    suspend fun correct(
        tabelaOrigem: String,
        registroId: String,
        conhecimentoAntigo: String?,
        conhecimentoNovo: String?,
        motivo: String?,
        chave: String? = null,
    ): MemoryCorrection = withContext(ioDispatcher) {
        val (token, userId) = requireSession()
        secureStore.update(tabelaOrigem, registroId, JSONObject().put("valid", false), token)
        secureStore.insert(
            "memory_corrections",
            MemoryCorrection(
                userId = userId,
                tabelaOrigem = tabelaOrigem,
                registroId = registroId,
                conhecimentoAntigo = conhecimentoAntigo,
                conhecimentoNovo = conhecimentoNovo,
                motivo = motivo,
                chave = chave,
            ).toInsertJson(),
            token,
        ).toMemoryCorrection()
    }

    suspend fun corrections(tabelaOrigem: String, registroId: String): List<MemoryCorrection> = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.select("memory_corrections", "tabela_origem=eq.$tabelaOrigem&registro_id=eq.$registroId&order=created_at.desc", token)
            .map { it.toMemoryCorrection() }
    }

    /**
     * Reflection Engine trigger (plan section 13, "comportamento antigo contradiz o novo
     * repetidamente"): how many times has [chave] in [tabelaOrigem] been corrected in the last
     * [sinceDays] days? A count of 1 is normal drift; 2+ means the same fact/preference keeps
     * flip-flopping, which is the "contradiction pattern" the plan asks to surface instead of
     * silently re-correcting forever. Returns 0 on any failure rather than throwing — this is an
     * advisory signal, not a correctness-critical path.
     */
    suspend fun correctionCountForChave(tabelaOrigem: String, chave: String, sinceDays: Int = 30): Int =
        withContext(ioDispatcher) {
            runCatching {
                val (token, _) = requireSession()
                val sinceIso = java.time.OffsetDateTime.now().minusDays(sinceDays.toLong()).toString()
                secureStore.select(
                    "memory_corrections",
                    "tabela_origem=eq.$tabelaOrigem&chave=eq.${chave.urlEncode()}&created_at=gte.$sinceIso",
                    token,
                ).length()
            }.getOrDefault(0)
        }

    /** Most recent corrections across every table — the "what changed and why" feed this data was
     * always meant to support (see the schema's own doc comment on memory_corrections) but that,
     * before this, nothing ever actually read back: every `correct()` call wrote a row here and it
     * just sat there, invisible to the user. */
    suspend fun recentCorrections(limit: Int = 20): List<MemoryCorrection> = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.select("memory_corrections", "order=created_at.desc&limit=$limit", token).map { it.toMemoryCorrection() }
    }

    // ---- Context (short-lived "what's true right now") ----

    suspend fun setContext(context: MemoryContext): MemoryContext = withContext(ioDispatcher) {
        val (token, userId) = requireSession()
        secureStore.insert("memory_context", context.copy(userId = userId).toInsertJson(), token).toMemoryContext()
    }

    suspend fun latestContext(chave: String): MemoryContext? = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.select("memory_context", "chave=eq.${chave.urlEncode()}&order=capturado_em.desc&limit=1", token)
            .map { it.toMemoryContext() }
            .firstOrNull()
    }

    // ---- Embeddings ----

    suspend fun addEmbedding(embedding: MemoryEmbedding): MemoryEmbedding = withContext(ioDispatcher) {
        val (token, userId) = requireSession()
        secureStore.insert("memory_embeddings", embedding.copy(userId = userId).toInsertJson(), token).toMemoryEmbedding()
    }

    /** Most recently indexed embeddings, newest first — an audit trail for the Debug screen (see
     * spec section 37) confirming what's actually being embedded, not just what the app *thinks* it
     * indexed. [MemoryEmbedding.embedding] on the results is always empty — the vector itself is
     * meaningless to a human and this is only ever used for [tabelaOrigem]/[registroId]/[modelo]/
     * [createdAt] — see [toMemoryEmbedding]'s tolerant parsing of that field. */
    suspend fun recentEmbeddings(limit: Int = 20): List<MemoryEmbedding> = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.select("memory_embeddings", "order=created_at.desc&limit=$limit", token).map { it.toMemoryEmbedding() }
    }

    /** Search indexing stays on-device: no memory text or derived vector is sent to an embedding API. */
    suspend fun indexForSearch(tabelaOrigem: String, registroId: String?, text: String) = Unit

    /** Local lexical relevance search. Queries and memory text remain on-device. */
    suspend fun semanticSearch(
        queryText: String,
        tables: List<String>? = null,
        limit: Int = 8,
    ): List<MemorySemanticMatch> {
        if (queryText.isBlank() || limit <= 0) return emptyList()
        return withContext(ioDispatcher) {
            val (token, _) = requireSession()
            val queryTokens = searchTokens(queryText)
            if (queryTokens.isEmpty()) return@withContext emptyList()
            secureStore.allRows(token, tables?.toSet()).asSequence()
                .filter { (table, _) -> table !in setOf("memory_embeddings", "memory_access_log") }
                .filter { (table, _) -> tables == null || table in tables }
                .filter { (_, row) -> row.optBoolean("valid", true) }
                .mapNotNull { (table, row) ->
                    val id = row.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
                    val searchable = buildString {
                        val keys = row.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            if (key !in SEARCH_METADATA_FIELDS) append(' ').append(row.opt(key)?.toString().orEmpty())
                        }
                    }
                    val rowTokens = searchTokens(searchable)
                    val overlap = queryTokens.count(rowTokens::contains)
                    if (overlap == 0) null else MemorySemanticMatch(
                        tabelaOrigem = table,
                        registroId = id,
                        similarity = overlap.toFloat() / queryTokens.size,
                    )
                }
                .sortedByDescending(MemorySemanticMatch::similarity)
                .take(limit)
                .toList()
            }
    }

    private fun searchTokens(value: String): Set<String> = value.lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 3 && it !in SEARCH_STOPWORDS }
        .toSet()

    /**
     * Semantic recall over past episodes, resolved to full [MemoryEpisode] rows and in the same
     * closest-first order [semanticSearch] returned — the concrete case buildMemoryDigest's static,
     * always-the-same-shape ranking can't cover: "did something like this happen before?" for
     * whatever the user is asking *right now*, not just the highest-importance facts on file.
     * Swallows failures to an empty list — same "nice-to-have,
     * never breaks the caller" discipline as [indexForSearch].
     */
    suspend fun semanticEpisodes(queryText: String, limit: Int = 5): List<MemoryEpisode> {
        val matches = runCatching { semanticSearch(queryText, tables = listOf("memory_episodes"), limit = limit) }
            .getOrDefault(emptyList())
        if (matches.isEmpty()) return emptyList()
        val ids = matches.map { it.registroId }
        val byId = episodesByIds(ids).associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    /** Fetches episodes by id, in one round trip — the shared lookup [semanticEpisodes] and the
     * Debug screen's search tester (which can't assume every match is an episode the way
     * [semanticEpisodes] does) both need. */
    suspend fun episodesByIds(ids: List<String>): List<MemoryEpisode> {
        if (ids.isEmpty()) return emptyList()
        return withContext(ioDispatcher) {
            val (token, _) = requireSession()
            secureStore.select(
                "memory_episodes",
                "valid=eq.true&id=in.(${ids.joinToString(",")})",
                token,
            ).map { it.toMemoryEpisode() }
        }
    }

    // ---- Long-term consolidation: pruning expired ephemeral data ----

    /**
     * Deletes rows in [tabelaOrigem] whose `expires_at` has already passed — the enforcement half
     * of what the schema already declares intent for (`memory_events`' doc comment: "expected to be
     * pruned aggressively ... once patterns are extracted into memory_routines"; `memory_context`'s:
     * "not raw history, this is what's true right now") but that, before this, nothing ever ran:
     * `expires_at` got set on insert and then just sat there forever. Deletes outright rather than
     * soft-invalidating via [correct] — an expired raw event or context snapshot has no lasting value
     * the way a corrected fact does, so there's nothing worth keeping a "what changed" trail for.
     * Returns how many rows were removed (for logging; callers don't need to react to the count).
     */
    suspend fun pruneExpired(tabelaOrigem: String): Int = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        val expired = secureStore.select(tabelaOrigem, "expires_at=lt.${java.time.OffsetDateTime.now()}", token)
        var deleted = 0
        for (i in 0 until expired.length()) {
            val id = expired.getJSONObject(i).optString("id").ifBlank { null } ?: continue
            runCatching { secureStore.delete(tabelaOrigem, id, token) }.onSuccess { deleted++ }
        }
        deleted
    }

    /**
     * Decay/desaprendizado (plan section 20) — deliberately the most conservative version possible,
     * since the plan itself warns against aggressive forgetting. Soft-invalidates (never deletes, so
     * [corrections]/audit history stays intact) rows in [tabelaOrigem] that satisfy ALL of:
     * confidence < 0.3, importance < 0.3, confirmed_by_user = false, and created_at older than
     * [olderThanDays] (default 180). Every one of those is required — a low-confidence row the user
     * explicitly confirmed, or a recent one, or one that's merely low-confidence but still somewhat
     * important, is left alone. This only runs for memory_facts/memory_preferences (see
     * [pruneExpiredMemory]'s call sites) — tables with no real "this went stale" story (people,
     * places, decisions, projects) aren't touched. Returns how many rows were decayed, for logging.
     */
    suspend fun decayStaleMemory(tabelaOrigem: String, olderThanDays: Int = 180): Int = withContext(ioDispatcher) {
        runCatching {
            val (token, _) = requireSession()
            val cutoff = java.time.OffsetDateTime.now().minusDays(olderThanDays.toLong())
            val stale = secureStore.select(
                tabelaOrigem,
                "valid=eq.true&confidence=lt.0.3&importance=lt.0.3&confirmed_by_user=eq.false&created_at=lt.$cutoff",
                token,
            )
            var decayed = 0
            for (i in 0 until stale.length()) {
                val id = stale.getJSONObject(i).optString("id").ifBlank { null } ?: continue
                runCatching { secureStore.update(tabelaOrigem, id, JSONObject().put("valid", false), token) }
                    .onSuccess { decayed++ }
            }
            decayed
        }.getOrDefault(0)
    }

    // ---- Notes ----

    suspend fun addNote(note: MemoryNote): MemoryNote = withContext(ioDispatcher) {
        val (token, userId) = requireSession()
        secureStore.insert("memory_notes", note.copy(userId = userId).toInsertJson(), token).toMemoryNote()
    }

    /** Active notes (not trashed), pinned first then most-recently-updated. [includeArchived]
     * defaults to false since the grid's default "Todas" filter is meant to read as "everything I'm
     * still keeping around", not literally every row. */
    suspend fun listNotes(includeArchived: Boolean = false): List<MemoryNote> = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        val archivedFilter = if (includeArchived) "" else "&arquivada=eq.false"
        secureStore.select("memory_notes", "valid=eq.true&trashed_at=is.null$archivedFilter&order=fixada.desc,updated_at.desc", token)
            .map { it.toMemoryNote() }
    }

    suspend fun listArchivedNotes(): List<MemoryNote> = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.select("memory_notes", "valid=eq.true&trashed_at=is.null&arquivada=eq.true&order=updated_at.desc", token)
            .map { it.toMemoryNote() }
    }

    suspend fun listTrashedNotes(): List<MemoryNote> = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.select("memory_notes", "valid=eq.true&trashed_at=not.is.null&order=trashed_at.desc", token)
            .map { it.toMemoryNote() }
    }

    /** Title/content search — checklist item text and OCR-in-images are out of scope for now (see
     * design notes), so this covers what's realistically searchable today. */
    suspend fun searchNotes(query: String): List<MemoryNote> = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        val q = query.urlEncode()
        secureStore.select(
            "memory_notes",
            "valid=eq.true&trashed_at=is.null&or=(titulo.ilike.*$q*,conteudo.ilike.*$q*)&order=fixada.desc,updated_at.desc",
            token,
        ).map { it.toMemoryNote() }
    }

    suspend fun findNoteByTitle(titulo: String): MemoryNote? = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.select("memory_notes", "valid=eq.true&trashed_at=is.null&titulo=ilike.${titulo.urlEncode()}", token)
            .map { it.toMemoryNote() }
            .firstOrNull()
    }

    suspend fun findNoteById(id: String): MemoryNote? = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.select(
            "memory_notes",
            "valid=eq.true&trashed_at=is.null&id=eq.${id.urlEncode()}&limit=1",
            token,
        ).let { rows -> if (rows.length() == 0) null else rows.getJSONObject(0).toMemoryNote() }
    }

    suspend fun updateNote(id: String, fields: JSONObject) = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.update("memory_notes", id, fields, token)
    }

    suspend fun trashNote(id: String) =
        updateNote(id, JSONObject().put("trashed_at", java.time.OffsetDateTime.now().toString()))

    suspend fun restoreNote(id: String) =
        updateNote(id, JSONObject().put("trashed_at", JSONObject.NULL))

    suspend fun deleteNotePermanently(id: String) = withContext(ioDispatcher) {
        val (token, _) = requireSession()
        secureStore.delete("memory_notes", id, token)
    }

    // ---- Legacy plaintext migration ----

    /** Best-effort: migrates every legacy plaintext memory table for the signed-in user to
     * encrypted storage. Call this once the account key becomes active (fresh setup or restore) so
     * a table nothing has happened to touch yet doesn't keep sitting in plaintext. Never throws —
     * a failure here just means the normal per-table migration (on first real use) catches it later. */
    suspend fun migrateAllLegacyData() {
        runCatching {
            withContext(ioDispatcher) {
                val (token, _) = requireSession()
                secureStore.migrateAllLegacyTables(token)
            }
        }
    }

    // ---- Access log ----

    /** Fire-and-forget: records a touch on a memory record. Never lets a logging failure break the caller. */
    suspend fun logAccess(tabelaOrigem: String, registroId: String, acao: String) {
        runCatching {
            withContext(ioDispatcher) {
                val (token, userId) = requireSession()
                secureStore.insert(
                    "memory_access_log",
                    MemoryAccessLogEntry(userId = userId, tabelaOrigem = tabelaOrigem, registroId = registroId, acao = acao).toInsertJson(),
                    token,
                )
            }
        }
    }

    /**
     * Marks a record as having actually informed a response: bumps `last_used_at` (the recency
     * signal [weightedScore] ranks on — nothing wrote to this column before this) and logs the touch
     * via [logAccess]. Fire-and-forget, same discipline as [logAccess] itself — a failed usage-mark
     * should never turn a successful response into a visible error.
     */
    suspend fun markUsed(tabelaOrigem: String, registroId: String) {
        runCatching {
            withContext(ioDispatcher) {
                val (token, _) = requireSession()
                secureStore.update(tabelaOrigem, registroId, JSONObject().put("last_used_at", java.time.OffsetDateTime.now().toString()), token)
            }
        }
        logAccess(tabelaOrigem, registroId, "used_in_response")
    }

    /**
     * How many times each of [ids] (all from [tabelaOrigem]) was actually used in a response —
     * see schema/003_access_counts.sql's `memory_access_counts`. The real "observationCount" signal
     * [weightedScore] was missing, read back from [markUsed]'s own log instead of a new column.
     * One batched RPC call for the whole set a ranking pass needs, not one query per record. Missing
     * ids (never used) simply aren't keys in the returned map — callers should treat an absent id as
     * 0, not as a failure. Swallows failures to an empty map, same "nice-to-have, never breaks the
     * caller" discipline as [semanticSearch].
     */
    suspend fun accessCounts(tabelaOrigem: String, ids: List<String>): Map<String, Int> {
        if (ids.isEmpty()) return emptyMap()
        return runCatching {
            withContext(ioDispatcher) {
                val (token, _) = requireSession()
                val idSet = ids.toSet()
                val rows = secureStore.select(
                    "memory_access_log",
                    "tabela_origem=eq.${tabelaOrigem.urlEncode()}",
                    token,
                )
                buildMap {
                    for (i in 0 until rows.length()) {
                        val row = rows.getJSONObject(i)
                        val id = row.optString("registro_id")
                        if (id in idSet && row.optString("acao") == "used_in_response") {
                            put(id, (get(id) ?: 0) + 1)
                        }
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private companion object {
        val SEARCH_METADATA_FIELDS = setOf(
            "id", "user_id", "origem", "confidence", "importance", "heat", "created_at", "updated_at",
            "last_used_at", "expires_at", "confirmed_by_user", "valid",
        )
        val SEARCH_STOPWORDS = setOf("para", "com", "que", "uma", "por", "dos", "das", "the", "and", "with", "from")
    }
}

private inline fun <T> org.json.JSONArray.map(transform: (JSONObject) -> T): List<T> =
    List(length()) { transform(getJSONObject(it)) }

private fun String.urlEncode(): String = java.net.URLEncoder.encode(this, "UTF-8")
