package com.unyx.atlas.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fase 2/3/7 of the Ilha/Círculo/Dock/Painel migration (docs `plano.md` v3, seção 17) — a
 * normalized projection of "something is happening", built by an adapter from a plugin's *real*
 * state. This is deliberately a small subset of the plan's full proposed shape (no `attention`,
 * `actions`, `sensitive`, `resultRoute`, `presentationExpiresAtEpochMillis` yet): those fields have
 * no consumer until later phases. Add them when a phase actually reads them.
 *
 * Lives in `core:model` (alongside [AppItem]) so `feature:home`'s battery widget can read whatever
 * activity is in progress without depending on whichever feature module published it.
 */

/** Identifies one execution. `generation` changes when the same [sourceId]/[itemId] starts a
 * genuinely new run (e.g. a fresh conversation turn) — so a late result from an old run can never
 * be mistaken for an update to a newer one. */
data class OccurrenceKey(
    val sourceId: String,
    val itemId: String,
    val generation: Long,
) {
    /** Fase 7 — identifies the *slot* this key belongs to, independent of which generation is
     * currently running in it. A new generation updates the same slot in place; it doesn't create
     * a second, concurrent one. */
    val slot: OccurrenceSlot get() = OccurrenceSlot(sourceId, itemId)
}

data class OccurrenceSlot(val sourceId: String, val itemId: String)

enum class ActivityPhase { RUNNING, WAITING_USER, SUCCEEDED, FAILED, CANCELLED }

sealed interface ActivityProgress {
    data object None : ActivityProgress
    data object Indeterminate : ActivityProgress
    data class Fraction(val value: Float) : ActivityProgress
}

data class Occurrence(
    val key: OccurrenceKey,
    /** Orders updates within the same [OccurrenceKey.generation]. When the source doesn't expose
     * its own revision, the adapter mints a serialized counter for its own session — see
     * `plano.md` §17.1: that orders arrival at the adapter, not necessarily the source's original
     * order for an async source with no versioning of its own. */
    val revision: Long,
    val phase: ActivityPhase,
    val title: String,
    val shortLabel: String,
    val progress: ActivityProgress,
)

/**
 * The anti-regression rule in pure-function form, so it's JVM-unit-testable without an emulator.
 *
 * A different [OccurrenceKey] entirely (different source/item) is never "stale" — it's simply a
 * different occurrence and always wins. Within the *same* key, a lower generation or a
 * lower-or-equal revision at the same generation is stale and must be rejected: that's what stops
 * a late result from an old run overwriting a newer one (`plano.md` §17.1, Fase 2 aceite).
 */
fun shouldReplace(current: Occurrence?, incoming: Occurrence): Boolean {
    if (current == null) return true
    if (current.key.sourceId != incoming.key.sourceId || current.key.itemId != incoming.key.itemId) return true
    if (incoming.key.generation != current.key.generation) return incoming.key.generation > current.key.generation
    return incoming.revision > current.revision
}

/** Fase 7 (`plano.md` v3 §19/23) — what a surface actually needs to render: a stable primary plus
 * every other eligible occurrence right now, excluding it (§19.4/23.3 — "a lista pode incluir
 * itens não exibidos externamente" for the "Outras atividades" list). */
data class OccurrenceRegistryState(
    val primary: Occurrence?,
    val others: List<Occurrence>,
) {
    val otherCount: Int get() = others.size
}

/**
 * Holds every currently-published [Occurrence] (Fase 7 — one per [OccurrenceSlot], across as many
 * concurrent sources as are active) plus a *stable* selection: the plan's own words are "preservar
 * a atividade atual do círculo enquanto continuar elegível" (§23.2) — a second source becoming
 * active must never steal the primary slot away from whatever's already showing. A plain
 * [StateFlow] rather than Compose state so any layer (a ViewModel, a plain Kotlin adapter, a
 * Composable) can read it without a Compose-runtime dependency.
 *
 * [publish]/[dismiss] keep the exact same signatures as Fase 2 — every one of the 7 migrated
 * plugin adapters calls only these two methods, so this internal upgrade from "one slot" to
 * "many slots with stable selection" needed no change to any of them.
 */
class OccurrenceRegistry {
    private val occurrences = LinkedHashMap<OccurrenceSlot, Occurrence>()
    // Fase 7 §20 "Dispensar apresentação": the last generation the user explicitly hid, per slot —
    // a refresh of that *same* generation must stay hidden; a genuinely new generation (a retry)
    // is a different run and is allowed through again.
    private val userDismissedGeneration = HashMap<OccurrenceSlot, Long>()
    private var selectedSlot: OccurrenceSlot? = null

    private val mutableState = MutableStateFlow(OccurrenceRegistryState(primary = null, others = emptyList()))
    val state: StateFlow<OccurrenceRegistryState> = mutableState.asStateFlow()

    fun publish(incoming: Occurrence) {
        val slot = incoming.key.slot
        val dismissedGeneration = userDismissedGeneration[slot]
        if (dismissedGeneration != null) {
            if (incoming.key.generation <= dismissedGeneration) return // still the dismissed run
            userDismissedGeneration.remove(slot) // a genuinely new run — dispensa doesn't carry over
        }
        if (!shouldReplace(occurrences[slot], incoming)) return
        occurrences[slot] = incoming
        if (selectedSlot == null || selectedSlot !in occurrences) selectedSlot = slot
        publishState()
    }

    /** Natural end of an occurrence's own lifecycle (source went idle/finished) — never treated as
     * a user action, so it does *not* set a dismissedGeneration marker (`plano.md` §20: fechar ≠
     * dispensar ≠ cancelar are three different effects). Only clears if [key] is still the one
     * published for its slot — a dismiss for a generation that was already superseded must not
     * wipe out whatever replaced it. */
    fun dismiss(key: OccurrenceKey) {
        val slot = key.slot
        if (occurrences[slot]?.key != key) return
        occurrences.remove(slot)
        promoteSelectionIfNeeded(slot)
        publishState()
    }

    /** Explicit "ocultar indicador" (`plano.md` §20/24.4) — unlike [dismiss], this is a user
     * choice: it remembers the generation so a mere refresh of the same run doesn't reappear, but
     * a later, genuinely new run at that slot is free to show again. */
    fun dismissByUser(key: OccurrenceKey) {
        val slot = key.slot
        userDismissedGeneration[slot] = key.generation
        if (occurrences[slot]?.key == key) {
            occurrences.remove(slot)
            promoteSelectionIfNeeded(slot)
            publishState()
        }
    }

    private fun promoteSelectionIfNeeded(vacatedSlot: OccurrenceSlot) {
        if (selectedSlot == vacatedSlot) selectedSlot = occurrences.keys.firstOrNull()
    }

    /** Explicit user choice from the "Outras atividades" list (`plano.md` §23.2: "Se o usuário
     * selecionar uma atividade na lista, mantê-la como preferência enquanto for válida"). Does
     * nothing if [key]'s slot isn't currently active — a stale selection from a closed list must
     * not resurrect anything. */
    fun selectPrimary(key: OccurrenceKey) {
        val slot = key.slot
        if (occurrences.containsKey(slot)) {
            selectedSlot = slot
            publishState()
        }
    }

    private fun publishState() {
        val primary = selectedSlot?.let(occurrences::get)
        val others = occurrences.entries.filter { it.key != selectedSlot }.map { it.value }
        mutableState.value = OccurrenceRegistryState(primary, others)
    }

    companion object {
        val instance = OccurrenceRegistry()
    }
}
