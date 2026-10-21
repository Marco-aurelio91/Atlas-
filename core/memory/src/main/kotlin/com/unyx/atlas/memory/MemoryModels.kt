package com.unyx.atlas.memory

/**
 * Fields every memory_* content table shares (see core/memory/schema/001_init.sql) — lets code
 * that only cares about lifecycle (e.g. a future heat-decay job, or "what did I actually use to
 * answer that") work across all memory kinds without switching on type. memory_embeddings and
 * memory_access_log are structurally different (pure references/log, no "content" of their own)
 * and intentionally don't implement this.
 */
interface MemoryMeta {
    val id: String?
    val userId: String
    val origem: String
    val confidence: Float
    val importance: Float
    val heat: Float
    val createdAt: String?
    val updatedAt: String?
    val lastUsedAt: String?
    val expiresAt: String?
    val confirmedByUser: Boolean
    val valid: Boolean
}

/** Identity/semantic/capability knowledge about the user or the assistant itself. */
data class MemoryFact(
    override val id: String? = null,
    override val userId: String = "default",
    /** 'identity' | 'semantic' | 'capability' | ... — freeform subtype within this one table. */
    val tipo: String = "semantic",
    val chave: String? = null,
    /** Raw JSON text (jsonb column) — parse further only if/when a caller actually needs to. */
    val conteudo: String,
    override val origem: String = "observed",
    override val confidence: Float = 1f,
    override val importance: Float = 0.5f,
    override val heat: Float = 0f,
    override val createdAt: String? = null,
    override val updatedAt: String? = null,
    override val lastUsedAt: String? = null,
    override val expiresAt: String? = null,
    override val confirmedByUser: Boolean = false,
    override val valid: Boolean = true,
) : MemoryMeta

/** How the user likes things done — always carries a confidence, never treated as an absolute rule. */
data class MemoryPreference(
    override val id: String? = null,
    override val userId: String = "default",
    val assunto: String,
    val preferencia: String,
    override val origem: String = "observed",
    override val confidence: Float = 0.5f,
    override val importance: Float = 0.5f,
    override val heat: Float = 0f,
    override val createdAt: String? = null,
    override val updatedAt: String? = null,
    override val lastUsedAt: String? = null,
    override val expiresAt: String? = null,
    override val confirmedByUser: Boolean = false,
    override val valid: Boolean = true,
) : MemoryMeta

/** A raw observed event (app opened, notification seen...) — high volume, feeds routine inference. */
data class MemoryEvent(
    override val id: String? = null,
    override val userId: String = "default",
    val evento: String,
    val payload: String? = null,
    override val origem: String = "observed",
    override val confidence: Float = 1f,
    override val importance: Float = 0.1f,
    override val heat: Float = 0f,
    override val createdAt: String? = null,
    override val updatedAt: String? = null,
    override val lastUsedAt: String? = null,
    override val expiresAt: String? = null,
    override val confirmedByUser: Boolean = false,
    override val valid: Boolean = true,
) : MemoryMeta

/** A specific thing that happened, worth recalling on its own ("ontem você pediu para..."). */
data class MemoryEpisode(
    override val id: String? = null,
    override val userId: String = "default",
    val data: String? = null,
    val evento: String,
    val assunto: String? = null,
    val acao: String? = null,
    val resultado: String? = null,
    override val origem: String = "observed",
    override val confidence: Float = 1f,
    override val importance: Float = 0.5f,
    override val heat: Float = 0f,
    override val createdAt: String? = null,
    override val updatedAt: String? = null,
    override val lastUsedAt: String? = null,
    override val expiresAt: String? = null,
    override val confirmedByUser: Boolean = false,
    override val valid: Boolean = true,
) : MemoryMeta

/** An inferred recurring pattern ("abre WhatsApp às 07:15, seg-sex, 82% das vezes"). */
data class MemoryRoutine(
    override val id: String? = null,
    override val userId: String = "default",
    val evento: String,
    /** "HH:mm:ss" */
    val horarioMedio: String? = null,
    val frequencia: Float = 0f,
    /** ISO weekday numbers, 1=Monday..7=Sunday. */
    val dias: List<Int> = emptyList(),
    val ultimaConfirmacao: String? = null,
    override val origem: String = "inferred",
    override val confidence: Float = 0.5f,
    override val importance: Float = 0.5f,
    override val heat: Float = 0f,
    override val createdAt: String? = null,
    override val updatedAt: String? = null,
    override val lastUsedAt: String? = null,
    override val expiresAt: String? = null,
    override val confirmedByUser: Boolean = false,
    override val valid: Boolean = true,
) : MemoryMeta

/** Someone in the user's life — not the same as a phone contact. */
data class MemoryPerson(
    override val id: String? = null,
    override val userId: String = "default",
    val nome: String,
    val relacao: String? = null,
    /** Digits where possible (country code included) — used by send_whatsapp_message's wa.me link. */
    val telefone: String? = null,
    val contexto: String? = null,
    val assuntosAssociados: List<String> = emptyList(),
    val ultimaInteracao: String? = null,
    override val origem: String = "observed",
    override val confidence: Float = 0.8f,
    override val importance: Float = 0.5f,
    override val heat: Float = 0f,
    override val createdAt: String? = null,
    override val updatedAt: String? = null,
    override val lastUsedAt: String? = null,
    override val expiresAt: String? = null,
    override val confirmedByUser: Boolean = false,
    override val valid: Boolean = true,
) : MemoryMeta

/** A place that matters to the user — User World Model's one new category (plan section 16); see
 * schema/005_places.sql for why Places specifically, not Devices/Apps/Interests/Skills too.
 * [endereco] is freeform text the user gave, never geocoded (this app has no mapping of its own) —
 * [aliases] mirrors [MemoryProject]'s own, so "o trampo" can resolve the same way "o launcher" does. */
data class MemoryPlace(
    override val id: String? = null,
    override val userId: String = "default",
    val nome: String,
    val endereco: String? = null,
    val aliases: List<String> = emptyList(),
    override val origem: String = "user_explicit",
    override val confidence: Float = 1f,
    override val importance: Float = 0.5f,
    override val heat: Float = 0f,
    override val createdAt: String? = null,
    override val updatedAt: String? = null,
    override val lastUsedAt: String? = null,
    override val expiresAt: String? = null,
    override val confirmedByUser: Boolean = true,
    override val valid: Boolean = true,
) : MemoryMeta

/** A project the user works on — with aliases, so "o launcher" resolves to Atlas Launcher.
 * [status]/[objetivo]/[proximaAcao] back the architecture spec's "continuidade de projetos"
 * (section 17): "vamos continuar aquele projeto" can resolve to a real next step, not just a name. */
data class MemoryProject(
    override val id: String? = null,
    override val userId: String = "default",
    val nome: String,
    val projetoPaiId: String? = null,
    val descricao: String? = null,
    val aliases: List<String> = emptyList(),
    /** 'ativo' | 'pausado' | 'concluido'. */
    val status: String = "ativo",
    val objetivo: String? = null,
    val proximaAcao: String? = null,
    /** "YYYY-MM-DD", nullable — most projects are open-ended; this is only set when the user
     * actually gave one. The one field a [MemoryProject] was missing to work as a Goal Model
     * (plan section 25) alongside [MemoryTask]'s own `prazo` — see ChatViewModel's
     * computeProactiveNudges, which now reads this the same way it reads a task's deadline. */
    val deadline: String? = null,
    override val origem: String = "user_explicit",
    override val confidence: Float = 1f,
    override val importance: Float = 0.5f,
    override val heat: Float = 0f,
    override val createdAt: String? = null,
    override val updatedAt: String? = null,
    override val lastUsedAt: String? = null,
    override val expiresAt: String? = null,
    override val confirmedByUser: Boolean = true,
    override val valid: Boolean = true,
) : MemoryMeta

/** A pending or in-progress work item (architecture spec section 27) — distinct from
 * [MemoryEpisode] (records what already happened) and optionally scoped to a [MemoryProject] via
 * [projetoId]. */
data class MemoryTask(
    override val id: String? = null,
    override val userId: String = "default",
    val titulo: String,
    val descricao: String? = null,
    val projetoId: String? = null,
    /** 'baixa' | 'normal' | 'alta'. */
    val prioridade: String = "normal",
    /** 'pending' | 'in_progress' | 'blocked' | 'completed' | 'cancelled'. */
    val status: String = "pending",
    /** "YYYY-MM-DD", nullable — not every task has a deadline. */
    val prazo: String? = null,
    val completedAt: String? = null,
    override val origem: String = "user_explicit",
    override val confidence: Float = 1f,
    override val importance: Float = 0.5f,
    override val heat: Float = 0f,
    override val createdAt: String? = null,
    override val updatedAt: String? = null,
    override val lastUsedAt: String? = null,
    override val expiresAt: String? = null,
    override val confirmedByUser: Boolean = true,
    override val valid: Boolean = true,
) : MemoryMeta

/** One checklist row inside [MemoryNote.checklist] — not its own table, just a jsonb array; a note's
 * checklist is small and always read/written whole, so there's no query that needs it normalized. */
data class NoteChecklistItem(
    val texto: String,
    val concluido: Boolean = false,
    val ordem: Int = 0,
)

/** A freeform note (architecture spec's "Notas" screen) — deliberately separate from [MemoryFact]
 * (stable, inferred facts about the user) and [MemoryTask] (status-tracked work): a note is
 * whatever the user actually wrote, kept in their own words and editable directly, not reformatted
 * or inferred. [tipo] distinguishes a plain note from a checklist-only one so the "Listas" filter is
 * unambiguous even for a checklist with zero items yet. [trashedAt] (non-null = in the lixeira) and
 * [arquivada] are user-driven organization states, kept separate from [MemoryMeta.valid] (which
 * means "superseded/obsolete" for every other memory kind — a different concept from "the user
 * deleted this"). */
data class MemoryNote(
    override val id: String? = null,
    override val userId: String = "default",
    /** 'nota' | 'checklist'. */
    val tipo: String = "nota",
    val titulo: String? = null,
    val conteudo: String? = null,
    val checklist: List<NoteChecklistItem> = emptyList(),
    val tags: List<String> = emptyList(),
    /** A color token (e.g. "amarelo"), not a raw hex value — see the notes color palette. Null = default. */
    val cor: String? = null,
    val fixada: Boolean = false,
    val arquivada: Boolean = false,
    val trashedAt: String? = null,
    val lembreteEm: String? = null,
    override val origem: String = "user_explicit",
    override val confidence: Float = 1f,
    override val importance: Float = 0.5f,
    override val heat: Float = 0f,
    override val createdAt: String? = null,
    override val updatedAt: String? = null,
    override val lastUsedAt: String? = null,
    override val expiresAt: String? = null,
    override val confirmedByUser: Boolean = true,
    override val valid: Boolean = true,
) : MemoryMeta

/** A standing behavioral rule ("não notificar no horário de trabalho") that persists until changed. */
data class MemoryDecision(
    override val id: String? = null,
    override val userId: String = "default",
    val regra: String,
    val periodo: String? = null,
    val confirmada: Boolean = false,
    override val origem: String = "user_explicit",
    override val confidence: Float = 1f,
    override val importance: Float = 0.7f,
    override val heat: Float = 0f,
    override val createdAt: String? = null,
    override val updatedAt: String? = null,
    override val lastUsedAt: String? = null,
    override val expiresAt: String? = null,
    override val confirmedByUser: Boolean = true,
    override val valid: Boolean = true,
) : MemoryMeta

/**
 * Records that some other memory record was superseded, and why — the old record itself is only
 * soft-invalidated (valid = false), never deleted, so "what changed and when" stays legible.
 */
data class MemoryCorrection(
    override val id: String? = null,
    override val userId: String = "default",
    val tabelaOrigem: String,
    val registroId: String,
    val conhecimentoAntigo: String? = null,
    val conhecimentoNovo: String? = null,
    val motivo: String? = null,
    /** The stable key this correction was about ("mora_em", an assunto, …) — [registroId] changes
     * on every correction (a fresh row each time), so counting *how many times this same thing has
     * been corrected* (Reflection Engine, plan section 13) needs this instead. Null for
     * corrections logged before this existed, and for tables where "key" isn't a meaningful
     * concept (save_person's correct() call, which has no stable name-based key of its own). */
    val chave: String? = null,
    override val origem: String = "user_explicit",
    override val confidence: Float = 1f,
    override val importance: Float = 0.6f,
    override val heat: Float = 0f,
    override val createdAt: String? = null,
    override val updatedAt: String? = null,
    override val lastUsedAt: String? = null,
    override val expiresAt: String? = null,
    override val confirmedByUser: Boolean = true,
    override val valid: Boolean = true,
) : MemoryMeta

/** A short-lived snapshot of "what's true right now" (current app, battery, location...). */
data class MemoryContext(
    override val id: String? = null,
    override val userId: String = "default",
    val chave: String,
    /** Raw JSON text (jsonb column). */
    val valor: String,
    val capturadoEm: String? = null,
    override val origem: String = "observed",
    override val confidence: Float = 1f,
    override val importance: Float = 0.2f,
    override val heat: Float = 0f,
    override val createdAt: String? = null,
    override val updatedAt: String? = null,
    override val lastUsedAt: String? = null,
    override val expiresAt: String? = null,
    override val confirmedByUser: Boolean = false,
    override val valid: Boolean = true,
) : MemoryMeta

/** A vector embedding for some other memory record — kept separate so swapping embedding models
 * never touches the content tables. */
data class MemoryEmbedding(
    val id: String? = null,
    val userId: String = "default",
    val tabelaOrigem: String,
    val registroId: String,
    val modelo: String,
    val embedding: List<Float>,
    val createdAt: String? = null,
)

/** One hit from [MemoryRepository.semanticSearch] — just enough to look up the real row
 * ([tabelaOrigem]/[registroId]) or rank/display it ([similarity], cosine similarity in 0..1,
 * higher = closer) without duplicating that row's own fields here. */
data class MemorySemanticMatch(
    val tabelaOrigem: String,
    val registroId: String,
    val similarity: Float,
)

/** One touch (read/write/used-in-response) on a memory record — the raw material "heat" is computed from. */
data class MemoryAccessLogEntry(
    val id: String? = null,
    val userId: String = "default",
    val tabelaOrigem: String,
    val registroId: String,
    /** 'read' | 'write' | 'used_in_response' */
    val acao: String,
    val acessadoEm: String? = null,
)
