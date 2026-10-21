package com.unyx.athena.core

import java.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Shared SDK contract. Subclasses supply a deterministic provider fixture, never live credentials. */
abstract class WorldConnectorContract {
    abstract fun connector(): WorldConnector
    abstract fun selector(): WorldSelector

    @Test fun `manifest declares bounded retention and stable source identity`() {
        val descriptor = connector().descriptor
        assertTrue(descriptor.source.connectorId.matches(Regex("[a-z0-9.-]{1,64}")))
        assertTrue(descriptor.source.host.isNotBlank())
        assertTrue(descriptor.retention in Duration.ofSeconds(1)..Duration.ofHours(1))
    }
    @Test fun `candidate has requested selector and compatible typed payload`() = runTest {
        val connector = connector()
        val selector = selector()
        assertTrue(connector.accepts(selector))
        val candidate = connector.fetch(selector)
        assertEquals(selector, candidate.selector)
        when (selector) {
            is WorldSelector.Service -> assertTrue(candidate.payload is WorldPayload.Service)
            is WorldSelector.Weather -> assertTrue(candidate.payload is WorldPayload.Weather)
        }
    }
    @Test fun `provider never manufactures derived evidence`() = runTest {
        assertFalse(connector().fetch(selector()).payload is WorldPayload.SustainedCold)
    }
}
