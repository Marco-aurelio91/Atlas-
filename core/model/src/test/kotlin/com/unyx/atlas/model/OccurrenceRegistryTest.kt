package com.unyx.atlas.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OccurrenceRegistryTest {

    private fun occurrence(generation: Long = 1, revision: Long = 1, sourceId: String = "Source", itemId: String = "item") =
        Occurrence(
            key = OccurrenceKey(sourceId, itemId, generation),
            revision = revision,
            phase = ActivityPhase.RUNNING,
            title = "T",
            shortLabel = "L",
            progress = ActivityProgress.None,
        )

    @Test fun nothingCurrentAlwaysAccepts() {
        assertTrue(shouldReplace(current = null, incoming = occurrence()))
    }

    @Test fun higherRevisionSameGenerationReplaces() {
        val current = occurrence(revision = 3)
        assertTrue(shouldReplace(current, occurrence(revision = 4)))
    }

    @Test fun lowerOrEqualRevisionSameGenerationIsStale() {
        val current = occurrence(revision = 5)
        assertFalse(shouldReplace(current, occurrence(revision = 5)))
        assertFalse(shouldReplace(current, occurrence(revision = 3)))
    }

    @Test fun higherGenerationReplacesRegardlessOfRevision() {
        // A new run (generation 2) starting fresh at revision 1 must still beat an old run
        // (generation 1) that had reached revision 99 — this is the exact bug this guards
        // against: a late result from an old run overwriting a newer one.
        val current = occurrence(generation = 1, revision = 99)
        assertTrue(shouldReplace(current, occurrence(generation = 2, revision = 1)))
    }

    @Test fun lowerGenerationIsAlwaysStaleEvenWithHigherRevision() {
        val current = occurrence(generation = 2, revision = 1)
        assertFalse(shouldReplace(current, occurrence(generation = 1, revision = 99)))
    }

    @Test fun differentKeyEntirelyAlwaysAccepted() {
        val current = occurrence(sourceId = "A", itemId = "x", revision = 99)
        assertTrue(shouldReplace(current, occurrence(sourceId = "B", itemId = "y", revision = 1)))
        assertTrue(shouldReplace(current, occurrence(sourceId = "A", itemId = "y", revision = 1)))
    }

    @Test fun registryPublishRejectsStaleAndDismissOnlyClearsMatchingKey() {
        val registry = OccurrenceRegistry()
        val first = occurrence(generation = 1, revision = 1)
        registry.publish(first)
        assertTrue(registry.state.value.primary === first)

        // Stale revision is ignored.
        registry.publish(occurrence(generation = 1, revision = 1))
        assertTrue(registry.state.value.primary === first)

        // A dismiss for a key that's no longer current does nothing.
        registry.dismiss(OccurrenceKey("Source", "item", generation = 0))
        assertTrue(registry.state.value.primary === first)

        // A dismiss matching the current key clears it.
        registry.dismiss(first.key)
        assertTrue(registry.state.value.primary == null)
    }

    @Test fun secondSourceNeverStealsTheStablePrimarySelection() {
        // plano.md v3 §23.2 — "preservar a atividade atual do círculo enquanto continuar elegível":
        // a second, unrelated source becoming active must not steal the primary slot.
        val registry = OccurrenceRegistry()
        val first = occurrence(sourceId = "A", itemId = "x")
        val second = occurrence(sourceId = "B", itemId = "y")
        registry.publish(first)
        registry.publish(second)
        assertTrue(registry.state.value.primary === first)
        assertEquals(1, registry.state.value.otherCount)
    }

    @Test fun dismissingThePrimaryPromotesAnotherEligibleOccurrence() {
        val registry = OccurrenceRegistry()
        val first = occurrence(sourceId = "A", itemId = "x")
        val second = occurrence(sourceId = "B", itemId = "y")
        registry.publish(first)
        registry.publish(second)
        registry.dismiss(first.key)
        assertTrue(registry.state.value.primary === second)
        assertEquals(0, registry.state.value.otherCount)
    }

    @Test fun dismissingAnOtherOccurrenceNeverTouchesThePrimary() {
        val registry = OccurrenceRegistry()
        val first = occurrence(sourceId = "A", itemId = "x")
        val second = occurrence(sourceId = "B", itemId = "y")
        registry.publish(first)
        registry.publish(second)
        registry.dismiss(second.key)
        assertTrue(registry.state.value.primary === first)
        assertEquals(0, registry.state.value.otherCount)
    }

    @Test fun userDismissHidesUntilAGenuinelyNewGenerationArrives() {
        val registry = OccurrenceRegistry()
        val first = occurrence(sourceId = "A", itemId = "x", generation = 1, revision = 1)
        registry.publish(first)
        registry.dismissByUser(first.key)
        assertTrue(registry.state.value.primary == null)

        // A refresh of the *same* generation stays hidden — a dispensa isn't undone by a mere
        // progress update (plano.md §20: "um refresh da mesma execução não" reaparece).
        registry.publish(occurrence(sourceId = "A", itemId = "x", generation = 1, revision = 2))
        assertTrue(registry.state.value.primary == null)

        // A genuinely new run (generation 2) is a different execution — free to show again.
        val retry = occurrence(sourceId = "A", itemId = "x", generation = 2, revision = 1)
        registry.publish(retry)
        assertTrue(registry.state.value.primary === retry)
    }

    @Test fun othersListExposesEveryNonPrimaryOccurrence() {
        val registry = OccurrenceRegistry()
        val first = occurrence(sourceId = "A", itemId = "x")
        val second = occurrence(sourceId = "B", itemId = "y")
        val third = occurrence(sourceId = "C", itemId = "z")
        registry.publish(first)
        registry.publish(second)
        registry.publish(third)
        assertTrue(registry.state.value.primary === first)
        assertEquals(listOf(second, third), registry.state.value.others)
    }

    @Test fun selectPrimaryHonorsAnExplicitUserChoiceFromTheList() {
        // plano.md v3 §23.2 — an explicit pick from "Outras atividades" becomes the new stable
        // selection, staying pinned even when it wasn't first-published.
        val registry = OccurrenceRegistry()
        val first = occurrence(sourceId = "A", itemId = "x")
        val second = occurrence(sourceId = "B", itemId = "y")
        registry.publish(first)
        registry.publish(second)
        registry.selectPrimary(second.key)
        assertTrue(registry.state.value.primary === second)
        assertEquals(listOf(first), registry.state.value.others)

        // A third source arriving afterward still doesn't steal the (now explicitly chosen) primary.
        val third = occurrence(sourceId = "C", itemId = "z")
        registry.publish(third)
        assertTrue(registry.state.value.primary === second)
    }

    @Test fun selectPrimaryIgnoresAKeyThatIsNotCurrentlyActive() {
        val registry = OccurrenceRegistry()
        val first = occurrence(sourceId = "A", itemId = "x")
        registry.publish(first)
        registry.selectPrimary(OccurrenceKey("Ghost", "gone", generation = 0))
        assertTrue(registry.state.value.primary === first)
    }
}
