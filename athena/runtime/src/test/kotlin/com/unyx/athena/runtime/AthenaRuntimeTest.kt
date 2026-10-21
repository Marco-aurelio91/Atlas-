package com.unyx.athena.runtime

import com.unyx.athena.core.*
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AthenaRuntimeTest {
    private val region = GeographicRegion(-2355, -4663)
    private val clock = MutableClock()
    private val connector = FakeConnector(clock)
    private val runtime = AthenaRuntime(connector, clock)

    @Test fun `pipeline validates publishes and caches without emitting duplicate on forced query`() = runTest {
        val events = mutableListOf<AthenaEvent>()
        val subscriber = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            runtime.events.observe().collect { events += it }
        }
        val first = request().payload as WorldQueryResult.Success
        val cached = request().payload as WorldQueryResult.Success
        val forced = request(maxAge = Duration.ZERO).payload as WorldQueryResult.Success
        testScheduler.runCurrent()
        assertFalse(first.fromCache)
        assertTrue(cached.fromCache)
        assertFalse(forced.fromCache)
        assertEquals(first.event.id, forced.event.id)
        assertEquals(2, connector.calls)
        assertEquals(1, events.size)
        assertNull(first.event.confidence)
        assertTrue(first.event.provenance.evidence.contains(TrustEvidence.SCHEMA_VALIDATED))
        assertEquals(1L, runtime.metrics.value.deduplicated)
        subscriber.cancel()
    }

    @Test fun `strict maxAge forces refresh and never falls back to stale cache on failure`() = runTest {
        request()
        clock.advance(Duration.ofMinutes(6))
        connector.error = AthenaError.UNAVAILABLE
        assertEquals(WorldQueryResult.Failure(AthenaError.UNAVAILABLE), request(maxAge = Duration.ofMinutes(5)).payload)
        assertEquals(2, connector.calls)
        assertEquals(SourceHealthState.UNAVAILABLE, runtime.sourceHealth.value?.state)
    }

    @Test fun `expired event is unavailable even when provider keeps returning old observation`() = runTest {
        request()
        clock.advance(Duration.ofHours(2))
        assertEquals(WorldQueryResult.Failure(AthenaError.STALE_DATA), request().payload)
        assertEquals(1L, runtime.metrics.value.rejected)
    }

    @Test fun `regions have separate cache entries and expired entries are queried again`() = runTest {
        request()
        request(GeographicRegion(100, 200))
        assertEquals(2, connector.calls)
        clock.advance(Duration.ofMinutes(31))
        connector.observedAt = clock.instant()
        assertFalse((request().payload as WorldQueryResult.Success).fromCache)
        assertEquals(3, connector.calls)
    }

    @Test fun `invalid number and mismatched region are rejected before publication`() = runTest {
        connector.conditions = WeatherConditions(Double.NaN, 0)
        assertEquals(WorldQueryResult.Failure(AthenaError.INVALID_DATA), request().payload)
        connector.conditions = WeatherConditions(20.0, 0)
        connector.overrideRegion = GeographicRegion(0, 0)
        assertEquals(WorldQueryResult.Failure(AthenaError.INVALID_DATA), request().payload)
        assertEquals(0L, runtime.metrics.value.published)
    }

    @Test fun `future timestamp and unknown WMO code are rejected`() = runTest {
        connector.observedAt = clock.instant().plusSeconds(301)
        assertEquals(WorldQueryResult.Failure(AthenaError.INVALID_DATA), request().payload)
        connector.observedAt = clock.instant()
        connector.conditions = WeatherConditions(20.0, 999)
        assertEquals(WorldQueryResult.Failure(AthenaError.INVALID_DATA), request().payload)
    }

    @Test fun `late observation cannot replace a newer one`() = runTest {
        request()
        clock.advance(Duration.ofMinutes(2))
        connector.observedAt = clock.instant().minusSeconds(600)
        assertEquals(WorldQueryResult.Failure(AthenaError.STALE_DATA), request(maxAge = Duration.ZERO).payload)
    }

    @Test fun `deadline cancels connector and caller cancellation is not swallowed`() = runTest {
        connector.hang = true
        assertEquals(WorldQueryResult.Failure(AthenaError.TIMEOUT), request(deadline = Duration.ofMillis(100)).payload)
        assertTrue(connector.cancelled)
        assertEquals(AthenaError.TIMEOUT, runtime.sourceHealth.value?.error)
        connector.cancelled = false
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { request() }
        pending.cancelAndJoin()
        assertTrue(connector.cancelled)
    }

    @Test fun `concurrent queries share cache fill and queued deadline is honored`() = runTest {
        connector.latency = 200
        val first = async { request() }
        val queued = async { request(deadline = Duration.ofMillis(50)) }
        val third = async { request() }
        assertEquals(WorldQueryResult.Failure(AthenaError.TIMEOUT), queued.await().payload)
        assertTrue(first.await().payload is WorldQueryResult.Success)
        assertTrue((third.await().payload as WorldQueryResult.Success).fromCache)
        assertEquals(1, connector.calls)
    }

    @Test fun `protocol rejects unknown versions without touching provider`() = runTest {
        val reply = runtime.query(envelope().copy(protocolVersion = 999))
        assertEquals(WorldQueryResult.Failure(AthenaError.UNSUPPORTED_VERSION), reply.payload)
        assertEquals("request-id", reply.correlationId)
        assertEquals(0, connector.calls)
    }

    @Test fun `source recovers after rate limit without leaking provider exception details`() = runTest {
        connector.error = AthenaError.RATE_LIMITED
        request()
        assertEquals(SourceHealthState.RATE_LIMITED, runtime.sourceHealth.value?.state)
        connector.error = null
        request()
        assertEquals(SourceHealthState.HEALTHY, runtime.sourceHealth.value?.state)
        assertEquals(0, runtime.sourceHealth.value?.consecutiveFailures)
    }

    private fun envelope(
        region: GeographicRegion = this.region,
        maxAge: Duration = Duration.ofMinutes(30),
        deadline: Duration = Duration.ofSeconds(10),
    ) = AtlasAthenaEnvelope(1, "request-id", AthenaMessageType.QUERY_REQUEST, clock.instant(), null, WorldQuery(region, maxAge, deadline))

    private suspend fun request(
        region: GeographicRegion = this.region,
        maxAge: Duration = Duration.ofMinutes(30),
        deadline: Duration = Duration.ofSeconds(10),
    ) = runtime.query(envelope(region, maxAge, deadline))

    private class MutableClock : Clock() {
        private var now = Instant.parse("2026-09-27T12:00:00Z")
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        fun advance(duration: Duration) { now = now.plus(duration) }
    }

    private class FakeConnector(clock: Clock) : AthenaConnector {
        override val manifest = ConnectorManifest(
            AthenaSource("fake.weather", "Fake", "weather.example"), 1,
            setOf(WorldDomain.WEATHER), setOf(ConnectorCapability.QUERY), setOf("weather.example"), Duration.ofMinutes(30),
        )
        var calls = 0
        var observedAt = clock.instant()
        var conditions = WeatherConditions(22.0, 3)
        var error: AthenaError? = null
        var overrideRegion: GeographicRegion? = null
        var hang = false
        var cancelled = false
        var latency = 0L
        override suspend fun query(query: WorldQuery): WeatherObservation {
            calls++
            if (hang) try { awaitCancellation() } finally { cancelled = true }
            delay(latency)
            error?.let { throw ConnectorException(it) }
            return WeatherObservation(overrideRegion ?: query.region, conditions, observedAt)
        }
    }
}
