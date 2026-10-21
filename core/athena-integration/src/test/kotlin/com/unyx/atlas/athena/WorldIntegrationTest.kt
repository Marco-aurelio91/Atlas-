package com.unyx.atlas.athena

import com.unyx.athena.core.*
import com.unyx.athena.runtime.*
import com.unyx.athena.weather.OpenMeteoConnector
import java.time.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WorldIntegrationTest {
    private val now = Instant.parse("2026-09-30T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val region = GeographicRegion(100, 200)

    @Test fun `existing weather bridge uses V2 resilience state and explicit V1 compatibility`() = runTest {
        var calls = 0
        val provider = OpenMeteoConnector {
            calls++
            """{"current_units":{"time":"unixtime","temperature_2m":"°C","weather_code":"wmo code"},"current":{"time":${now.epochSecond},"temperature_2m":24,"weather_code":0}}"""
        }
        val runtime = WorldRuntime(listOf(WeatherWorldConnector(provider)), clock = clock)
        val bridge = AtlasAthenaBridge(WorldWeatherProtocol(runtime, clock), clock)
        val first = bridge.weather(region) as WorldQueryResult.Success
        val second = bridge.weather(region) as WorldQueryResult.Success
        assertEquals(24.0, first.event.weather.temperatureC, 0.0)
        assertEquals(1, first.event.schemaVersion)
        assertTrue(second.fromCache)
        assertEquals(1, calls)
        assertEquals(2, runtime.state.value.single().schemaVersion)
    }

    @Test fun `policy respects opt in quiet expiry relevance dismiss and resolution`() {
        val selector = WorldSelector.Service("github")
        val event = WorldEvent("id", selector, WorldPayload.Service(ServiceCondition.MAJOR_OUTAGE),
            AthenaSource("github.status", "GitHub", "www.githubstatus.com"), now, now, now.plusSeconds(900))
        val context = WorldPresentationContext(true, false, setOf(selector))
        assertNotNull(WorldPresentationPolicy.decide(event, context, now))
        for (blocked in listOf(context.copy(enabled = false), context.copy(quiet = true), context.copy(watched = emptySet()), context.copy(dismissed = setOf("id")))) {
            assertNull(WorldPresentationPolicy.decide(event, blocked, now))
        }
        assertNull(WorldPresentationPolicy.decide(event, context, now.plusSeconds(900)))
        assertNull(WorldPresentationPolicy.decide(event.copy(payload = WorldPayload.Service(ServiceCondition.OPERATIONAL)), context, now))
        val repeated = event.copy(id = "new-observation", observedAt = now.plusSeconds(60))
        assertNull(WorldPresentationPolicy.decide(repeated,
            context.copy(dismissed = setOf(WorldPresentationPolicy.suppressionKey(event))), now.plusSeconds(60)))
    }
}
