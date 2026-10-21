package com.unyx.athena.runtime

import com.unyx.athena.core.*
import java.nio.file.Files
import java.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WorldPersistenceCorrelationTest {
    private val clock = TestClock()
    private val selector = WorldSelector.Weather(GeographicRegion(100, 200))
    private val connector = TestConnector(selector, clock)

    @Test fun `atomic file roundtrip corruption and unsupported schema fail closed`() = runTest {
        val directory = Files.createTempDirectory("athena-store-test").toFile()
        val file = directory.resolve("world.json")
        try {
            val store = FileWorldStore(file)
            val runtime = WorldRuntime(listOf(connector), store, clock)
            runtime.query(WorldRequest(selector))
            runtime.subscribe(WorldSubscription("watch", selector, clock.instant().plusSeconds(600)))
            val snapshot = store.load()
            assertEquals(runtime.state.value, snapshot.events)
            assertEquals(1, snapshot.subscriptions.size)
            assertFalse(directory.resolve("world.json.tmp").exists())
            file.writeText("{\"version\":999,\"events\":[],\"subscriptions\":[]}")
            try { store.load(); fail() } catch (_: IllegalArgumentException) { }
            file.writeText("[".repeat(1000))
            val restart = WorldRuntime(listOf(connector), store, clock)
            restart.maintain()
            assertTrue(restart.state.value.isEmpty())
            assertEquals(1L, restart.diagnostics.value.storageFailures)
            assertTrue(store.load().events.isEmpty())
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    @Test fun `sustained cold preserves evidence and is invalidated by warmer observation`() = runTest {
        connector.payload = WorldPayload.Weather(WeatherConditions(-2.0, 0))
        val runtime = WorldRuntime(listOf(connector), clock = clock)
        val first = (runtime.query(WorldRequest(selector)) as WorldReply.Success).event
        clock.advance(900)
        val second = (runtime.query(WorldRequest(selector, Duration.ZERO)) as WorldReply.Success).event
        val derived = runtime.state.value.single { it.payload is WorldPayload.SustainedCold }
        assertEquals(listOf(first.id, second.id), derived.evidence.map { it.eventId })
        assertEquals(first.expiresAt, derived.expiresAt)
        assertNull(derived.confidence)
        assertEquals(derived, ColdCorrelation.derive(first, second, clock.instant()))
        assertNull(ColdCorrelation.derive(first, first, clock.instant()))
        clock.advance(60)
        connector.payload = WorldPayload.Weather(WeatherConditions(2.0, 0))
        runtime.query(WorldRequest(selector, Duration.ZERO))
        assertTrue(runtime.state.value.none { it.payload is WorldPayload.SustainedCold })
    }

    @Test fun `correlation rejects missing expired mismatched and contradictory evidence`() = runTest {
        connector.payload = WorldPayload.Weather(WeatherConditions(-2.0, 0))
        val runtime = WorldRuntime(listOf(connector), clock = clock)
        val first = (runtime.query(WorldRequest(selector)) as WorldReply.Success).event
        clock.advance(900)
        val second = (runtime.query(WorldRequest(selector, Duration.ZERO)) as WorldReply.Success).event
        assertNull(ColdCorrelation.derive(null, second, clock.instant()))
        assertNull(ColdCorrelation.derive(first.copy(expiresAt = clock.instant()), second, clock.instant()))
        assertNull(ColdCorrelation.derive(first.copy(selector = WorldSelector.Weather(GeographicRegion(0, 0))), second, clock.instant()))
        assertNull(ColdCorrelation.derive(first.copy(payload = WorldPayload.Weather(WeatherConditions(1.0, 0))), second, clock.instant()))
        assertNull(ColdCorrelation.derive(first.copy(source = first.source.copy(connectorId = "other")), second, clock.instant()))
    }
}
