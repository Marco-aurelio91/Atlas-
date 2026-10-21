package com.unyx.atlas.feature.chat.agent

import com.unyx.atlas.memory.MemoryEpisode
import com.unyx.atlas.memory.MemoryRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reflection Engine (ATLAS_PLANO_MELHORIAS_EVOLUCAO_COGNITIVA.md section 13) — minimal,
 * data-grounded slice. Its most tractable trigger, "comportamento antigo contradiz o novo
 * repetidamente", already has everything it needs in memory_corrections once a row carries a
 * stable `chave` (see [MemoryRepository.correctionCountForChave]'s doc) — this class is just the
 * glue that reads that count back and, when a key keeps flip-flopping, writes a [MemoryEpisode]
 * the user can actually see (same place the Outcome Engine's episodes land) instead of the pattern
 * staying invisible inside an audit table nothing ever queries.
 *
 * Deliberately scoped down from the plan's full description (no cross-memory pattern mining, no
 * "detecta vieses" heuristics) — there's no real usage data yet to validate anything fancier
 * against, and a wrong contradiction call is just a slightly confusing episode, not a bad action.
 */
@Singleton
class ReflectionEngine @Inject constructor(
    private val memoryRepository: MemoryRepository,
) {
    /**
     * Call this right after a genuine (non-reinforcement) correction to [chave] in [tabelaOrigem].
     * Never throws — this is an advisory signal layered on top of save_fact/save_preference/etc.,
     * never part of their own correctness, so a failure here must stay invisible to the caller.
     */
    suspend fun checkContradiction(tabelaOrigem: String, chave: String?) {
        if (chave.isNullOrBlank()) return
        runCatching {
            val count = memoryRepository.correctionCountForChave(tabelaOrigem, chave)
            if (count >= REPEATED_CORRECTION_THRESHOLD) {
                memoryRepository.addEpisode(
                    MemoryEpisode(
                        evento = "Padrão detectado",
                        assunto = chave,
                        acao = tabelaOrigem,
                        resultado = "\"$chave\" mudou $count vezes nos últimos 30 dias — " +
                            "pode valer confirmar com o usuário em vez de seguir corrigindo em silêncio.",
                        origem = "inferred",
                        confidence = 0.6f,
                        importance = 0.3f,
                    ),
                )
            }
        }
    }

    companion object {
        private const val REPEATED_CORRECTION_THRESHOLD = 2
    }
}
