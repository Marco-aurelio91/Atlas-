package com.unyx.athena.runtime

import com.unyx.athena.core.*
import java.time.Duration

class WeatherContractTest : WorldConnectorContract() {
    override fun selector() = WorldSelector.Weather(GeographicRegion(100, 200))
    override fun connector() = WeatherWorldConnector(object : AthenaConnector {
        override val manifest = ConnectorManifest(AthenaSource("fixture.weather", "Fixture", "example.invalid"), 1,
            setOf(WorldDomain.WEATHER), setOf(ConnectorCapability.QUERY), setOf("example.invalid"), Duration.ofMinutes(30))
        override suspend fun query(query: WorldQuery) = WeatherObservation(query.region, WeatherConditions(20.0, 0), TestClock().instant())
    })
}

/** Third connector example: no change to runtime, bridge, personal memory or Android UI. */
class ExampleServiceContractTest : WorldConnectorContract() {
    override fun selector() = WorldSelector.Service("example")
    override fun connector(): WorldConnector = TestConnector(selector(), TestClock())
}
