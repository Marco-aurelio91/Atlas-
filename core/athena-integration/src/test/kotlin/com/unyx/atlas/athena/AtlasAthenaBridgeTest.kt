package com.unyx.atlas.athena

import com.unyx.athena.core.*
import com.unyx.athena.runtime.AthenaRuntime
import com.unyx.athena.weather.OpenMeteoConnector
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AtlasAthenaBridgeTest {
    private val now = Instant.ofEpochSecond(1790510400)
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val region = GeographicRegion(-2355, -4663)

    @Test fun `fake HTTP provider crosses real connector gateway cache protocol and ATLAS bridge`() = runTest {
        var calls = 0
        val connector = OpenMeteoConnector {
            calls++
            """{"current_units":{"time":"unixtime","temperature_2m":"°C","weather_code":"wmo code"},"current":{"time":1790510400,"temperature_2m":24,"weather_code":0}}"""
        }
        val bridge = AtlasAthenaBridge(AthenaRuntime(connector, clock), clock)
        val first = bridge.weather(region) as WorldQueryResult.Success
        val second = bridge.weather(region) as WorldQueryResult.Success
        assertEquals(24.0, first.event.weather.temperatureC, 0.0)
        assertEquals("open-meteo.weather", first.event.provenance.source.connectorId)
        assertEquals(1, calls)
        assertTrue(second.fromCache)
        assertEquals(SourceHealthState.HEALTHY, bridge.sourceHealth.value?.state)
    }

    @Test fun `bridge rejects unsupported version wrong correlation wrong region and expired data`() = runTest {
        for (fault in listOf("version", "correlation", "region", "expiry")) {
            val protocol = object : AtlasAthenaProtocol {
                override val sourceHealth = MutableStateFlow<SourceHealth?>(null)
                override val events = object : AthenaEventBus { override fun observe() = emptyFlow<AthenaEvent>() }
                override suspend fun query(request: AtlasAthenaEnvelope<WorldQuery>): AtlasAthenaEnvelope<WorldQueryResult> {
                    val event = AthenaEvent(
                        "id", 1, AthenaEventType.CURRENT_WEATHER, now, now,
                        if (fault == "region") GeographicRegion(0, 0) else region,
                        WeatherConditions(20.0, 0), EventSeverity.INFO, null,
                        EventProvenance(AthenaSource("fake", "Fake", "fake.example"), now, emptySet()),
                        if (fault == "expiry") now else now.plusSeconds(60),
                    )
                    return AtlasAthenaEnvelope(
                        if (fault == "version") 999 else 1, "response-id", AthenaMessageType.QUERY_RESULT,
                        now, if (fault == "correlation") "wrong-id" else request.messageId,
                        WorldQueryResult.Success(event, false),
                    )
                }
            }
            val result = AtlasAthenaBridge(protocol, clock).weather(region)
            assertTrue("Accepted $fault", result is WorldQueryResult.Failure)
        }
    }
}
