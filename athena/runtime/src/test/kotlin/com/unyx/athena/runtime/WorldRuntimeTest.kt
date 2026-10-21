package com.unyx.athena.runtime

import com.unyx.athena.core.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WorldRuntimeTest {
    private val clock = TestClock()
    private val weather = TestConnector(WorldSelector.Weather(GeographicRegion(100, 200)), clock)
    private val service = TestConnector(WorldSelector.Service("test"), clock)
    private fun runtime(store: WorldStore = MemoryWorldStore(), attempts: Int = 1) = WorldRuntime(listOf(weather, service), store, clock,
        ResiliencePolicy(attempts = attempts), jitter = { 0 })

    @Test fun `sources execute independently and queued deadline bounds lock wait`() = runTest {
        val runtime = runtime()
        weather.latency = 1000
        val slow = async { runtime.query(WorldRequest(weather.selector)) }
        testScheduler.runCurrent()
        val fast = runtime.query(WorldRequest(service.selector))
        assertTrue(fast is WorldReply.Success)
        assertFalse(slow.isCompleted)
        assertEquals(WorldReply.Failure(AthenaError.TIMEOUT), runtime.query(WorldRequest(weather.selector, deadline = Duration.ofMillis(50))))
        assertTrue(slow.await() is WorldReply.Success)
    }

    @Test fun `rate limit honors cooldown and does not retry inside same query`() = runTest {
        val runtime = runtime(attempts = 3)
        service.error = ConnectorException(AthenaError.RATE_LIMITED, Duration.ofSeconds(120))
        runtime.query(WorldRequest(service.selector))
        service.error = null
        assertEquals(WorldReply.Failure(AthenaError.RATE_LIMITED), runtime.query(WorldRequest(service.selector)))
        assertEquals(1, service.calls)
        clock.advance(121)
        assertTrue(runtime.query(WorldRequest(service.selector)) is WorldReply.Success)
        assertEquals(0, runtime.health.value[service.descriptor.source.connectorId]?.failures)
    }

    @Test fun `bounded retries circuit opening and half open recovery`() = runTest {
        val runtime = runtime(attempts = 3)
        service.error = ConnectorException(AthenaError.UNAVAILABLE)
        repeat(3) { runtime.query(WorldRequest(service.selector)) }
        assertEquals(9, service.calls)
        assertEquals(CircuitState.OPEN, runtime.health.value[service.descriptor.source.connectorId]?.circuit)
        runtime.query(WorldRequest(service.selector))
        assertEquals(9, service.calls)
        clock.advance(61)
        service.error = null
        assertTrue(runtime.query(WorldRequest(service.selector)) is WorldReply.Success)
        assertEquals(10, service.calls)
        assertEquals(CircuitState.CLOSED, runtime.health.value[service.descriptor.source.connectorId]?.circuit)
    }

    @Test fun `invalid payload is not retried cached or published`() = runTest {
        val runtime = runtime(attempts = 3)
        weather.payload = WorldPayload.Service(ServiceCondition.OPERATIONAL)
        assertEquals(WorldReply.Failure(AthenaError.INVALID_DATA), runtime.query(WorldRequest(weather.selector)))
        assertEquals(1, weather.calls)
        assertTrue(runtime.state.value.isEmpty())
        assertEquals(1L, runtime.diagnostics.value.rejected)
    }

    @Test fun `repeated state refresh updates freshness without duplicate change notification`() = runTest {
        val runtime = runtime()
        val events = mutableListOf<WorldEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { runtime.changes.collect { events += it } }
        runtime.query(WorldRequest(service.selector))
        testScheduler.runCurrent()
        clock.advance(60)
        runtime.query(WorldRequest(service.selector, Duration.ZERO))
        testScheduler.runCurrent()
        assertEquals(1, events.size)
        assertEquals(clock.instant(), runtime.state.value.single().observedAt)
        service.payload = WorldPayload.Service(ServiceCondition.DEGRADED)
        clock.advance(1)
        runtime.query(WorldRequest(service.selector, Duration.ZERO))
        testScheduler.runCurrent()
        assertEquals(2, events.size)
    }

    @Test fun `restart restores fresh state but does not revive expired state or subscriptions`() = runTest {
        val store = MemoryWorldStore()
        val first = runtime(store)
        first.query(WorldRequest(service.selector))
        first.subscribe(WorldSubscription("test", service.selector, clock.instant().plusSeconds(600)))
        val second = runtime(store)
        assertTrue((second.query(WorldRequest(service.selector)) as WorldReply.Success).fromCache)
        assertEquals(1, second.subscriptions.value.size)
        clock.advance(1801)
        val third = runtime(store)
        third.maintain()
        assertTrue(third.state.value.isEmpty())
        assertTrue(third.subscriptions.value.isEmpty())
        assertTrue(store.load().events.isEmpty())
    }

    @Test fun `cancel while polling suppresses delivery and future polls`() = runTest {
        val runtime = runtime()
        service.latency = 1000
        runtime.subscribe(WorldSubscription("test", service.selector, clock.instant().plusSeconds(600)))
        val pass = async { runtime.poll() }
        testScheduler.runCurrent()
        runtime.cancel("test")
        assertTrue(pass.await().isEmpty())
        assertTrue(runtime.poll().isEmpty())
        assertEquals(1, service.calls)
    }

    @Test fun `subscriptions group selectors expire and reject excessive lifetimes`() = runTest {
        val runtime = runtime()
        runtime.subscribe(WorldSubscription("a", service.selector, clock.instant().plusSeconds(600)))
        runtime.subscribe(WorldSubscription("b", service.selector, clock.instant().plusSeconds(600)))
        assertEquals(1, runtime.poll().size)
        assertEquals(1, service.calls)
        try { runtime.subscribe(WorldSubscription("bad", service.selector, clock.instant().plusSeconds(86401))); fail() } catch (_: IllegalArgumentException) { }
        clock.advance(601)
        assertTrue(runtime.poll().isEmpty())
    }

    @Test fun `deadline cancels retries and caller cancellation propagates`() = runTest {
        val runtime = runtime(attempts = 3)
        service.error = ConnectorException(AthenaError.UNAVAILABLE)
        assertEquals(WorldReply.Failure(AthenaError.TIMEOUT), runtime.query(WorldRequest(service.selector, deadline = Duration.ofMillis(50))))
        assertEquals(1, service.calls)
        service.error = null; service.latency = 10_000
        val call = launch { runtime.query(WorldRequest(service.selector)) }
        testScheduler.runCurrent()
        call.cancelAndJoin()
        assertTrue(call.isCancelled)
        assertTrue(runtime.state.value.isEmpty())
    }

    @Test fun `disk failure prevents publication and retains previously accepted state`() = runTest {
        var failSave = false
        val backing = MemoryWorldStore()
        val store = object : WorldStore {
            override fun load() = backing.load()
            override fun save(snapshot: WorldSnapshot) { if (failSave) error("disk full") else backing.save(snapshot) }
        }
        val runtime = runtime(store)
        runtime.query(WorldRequest(service.selector))
        val before = runtime.state.value
        failSave = true
        service.payload = WorldPayload.Service(ServiceCondition.DEGRADED)
        assertEquals(WorldReply.Failure(AthenaError.UNAVAILABLE), runtime.query(WorldRequest(service.selector, Duration.ZERO)))
        assertEquals(before, runtime.state.value)
        assertEquals(1L, runtime.diagnostics.value.storageFailures)
        clock.advance(1801)
        runtime.maintain()
        assertTrue(runtime.state.value.isEmpty())
    }

    @Test fun `unknown protocol and selector never touch a provider`() = runTest {
        val runtime = runtime()
        assertEquals(WorldReply.Failure(AthenaError.UNSUPPORTED_VERSION), runtime.query(WorldRequest(service.selector, version = 3)))
        assertEquals(WorldReply.Failure(AthenaError.INVALID_DATA), runtime.query(WorldRequest(WorldSelector.Service("missing"))))
        assertEquals(0, service.calls)
    }

    @Test fun `old revisions never replace newer status and stale weather is not refreshed by retrieval`() = runTest {
        val runtime = runtime()
        runtime.query(WorldRequest(service.selector))
        service.occurredAt = clock.instant().minusSeconds(1)
        assertEquals(WorldReply.Failure(AthenaError.STALE_DATA), runtime.query(WorldRequest(service.selector, Duration.ZERO)))
        weather.occurredAt = clock.instant().minusSeconds(3601)
        assertEquals(WorldReply.Failure(AthenaError.STALE_DATA), runtime.query(WorldRequest(weather.selector)))
    }

    @Test fun `bounded state remains stable after many refreshes`() = runTest {
        val runtime = runtime()
        repeat(200) { runtime.query(WorldRequest(service.selector, Duration.ZERO)) }
        assertEquals(1, runtime.state.value.size)
        assertEquals(199L, runtime.diagnostics.value.duplicates)
    }

    @Test fun `third connector needs no changes to runtime or personal core`() = runTest {
        val third = TestConnector(WorldSelector.Service("third"), clock)
        val runtime = WorldRuntime(listOf(weather, service, third), clock = clock)
        for (connector in listOf(weather, service, third)) {
            val reply = runtime.query(WorldRequest(connector.selector)) as WorldReply.Success
            assertEquals(connector.descriptor.source, reply.event.source)
            assertTrue(reply.event.fresh(clock.instant()))
            assertNull(reply.event.confidence)
        }
        assertEquals(3, runtime.state.value.size)
    }

    @Test fun `restore rejects tampered source id payload validity and event version`() = runTest {
        val store = MemoryWorldStore()
        runtime(store).query(WorldRequest(service.selector))
        val accepted = store.load().events.single()
        for (bad in listOf(accepted.copy(id = "forged"), accepted.copy(schemaVersion = 1),
            accepted.copy(expiresAt = clock.instant().plusSeconds(99999)), accepted.copy(source = accepted.source.copy(host = "evil.example")))) {
            store.save(WorldSnapshot(listOf(bad)))
            val runtime = runtime(store); runtime.maintain()
            assertTrue(runtime.state.value.isEmpty())
        }
    }
}

internal class TestClock : Clock() {
    private var now = Instant.parse("2026-09-30T12:00:00Z")
    override fun instant() = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    fun advance(seconds: Long) { now = now.plusSeconds(seconds) }
}
internal class TestConnector(val selector: WorldSelector, private val clock: Clock) : WorldConnector {
    override val descriptor = WorldManifest(AthenaSource(
        if (selector is WorldSelector.Service) selector.id else "weather", "Test", "test.example"), Duration.ofMinutes(30))
    override fun accepts(selector: WorldSelector) = this.selector == selector
    var payload: WorldPayload = if (selector is WorldSelector.Weather) WorldPayload.Weather(WeatherConditions(20.0, 0)) else WorldPayload.Service(ServiceCondition.OPERATIONAL)
    var error: ConnectorException? = null
    var calls = 0
    var latency = 0L
    var occurredAt: Instant? = null
    override suspend fun fetch(selector: WorldSelector): WorldCandidate {
        calls++; delay(latency); error?.let { throw it }
        return WorldCandidate(selector, payload, occurredAt ?: clock.instant())
    }
}
