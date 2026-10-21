package com.unyx.athena.runtime

import com.unyx.athena.core.*

/** Adapts the original weather connector without duplicating HTTP/provider logic. */
class WeatherWorldConnector(private val connector: AthenaConnector) : WorldConnector {
    override val descriptor = WorldManifest(connector.manifest.source, connector.manifest.retention)
    override fun accepts(selector: WorldSelector) = selector is WorldSelector.Weather
    override suspend fun fetch(selector: WorldSelector): WorldCandidate {
        val region = (selector as? WorldSelector.Weather)?.region ?: throw ConnectorException(AthenaError.INVALID_DATA)
        val result = connector.query(WorldQuery(region))
        return WorldCandidate(WorldSelector.Weather(result.region), WorldPayload.Weather(result.conditions), result.occurredAt)
    }
}
