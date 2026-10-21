package com.unyx.atlas.athena

import com.unyx.athena.core.*
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.flow.*

/** Explicit V1 compatibility endpoint backed by V2 state, resilience and storage. */
class WorldWeatherProtocol(private val world: WorldProtocol, private val clock: Clock = Clock.systemUTC()) : AtlasAthenaProtocol {
    private val mutableHealth = MutableStateFlow<SourceHealth?>(null)
    override val sourceHealth = mutableHealth.asStateFlow()
    override val events = object : AthenaEventBus {
        override fun observe(): Flow<AthenaEvent> = world.changes.mapNotNull { it.legacy() }
    }
    override suspend fun query(request: AtlasAthenaEnvelope<WorldQuery>): AtlasAthenaEnvelope<WorldQueryResult> {
        val result = when {
            request.protocolVersion != ATHENA_PROTOCOL_VERSION -> WorldQueryResult.Failure(AthenaError.UNSUPPORTED_VERSION)
            request.type != AthenaMessageType.QUERY_REQUEST -> WorldQueryResult.Failure(AthenaError.INVALID_DATA)
            else -> when (val reply = world.query(WorldRequest(WorldSelector.Weather(request.payload.region), request.payload.maxAge, request.payload.deadline))) {
                is WorldReply.Failure -> WorldQueryResult.Failure(reply.error)
                is WorldReply.Success -> reply.event.legacy()?.let { WorldQueryResult.Success(it, reply.fromCache) }
                    ?: WorldQueryResult.Failure(AthenaError.INVALID_DATA)
            }
        }
        val sourceId = (result as? WorldQueryResult.Success)?.event?.provenance?.source?.connectorId ?: "open-meteo.weather"
        val now = clock.instant()
        val previous = mutableHealth.value
        mutableHealth.value = when (result) {
            is WorldQueryResult.Success -> SourceHealth(sourceId, SourceHealthState.HEALTHY, now, previous?.lastFailureAt, 0, null)
            is WorldQueryResult.Failure -> SourceHealth(sourceId,
                if (result.error == AthenaError.RATE_LIMITED) SourceHealthState.RATE_LIMITED else SourceHealthState.UNAVAILABLE,
                previous?.lastSuccessAt, now, (previous?.consecutiveFailures ?: 0) + 1, result.error)
        }
        return AtlasAthenaEnvelope(ATHENA_PROTOCOL_VERSION, UUID.randomUUID().toString(), AthenaMessageType.QUERY_RESULT,
            now, request.messageId, result)
    }
    private fun WorldEvent.legacy(): AthenaEvent? {
        val weather = payload as? WorldPayload.Weather ?: return null
        val region = (selector as? WorldSelector.Weather)?.region ?: return null
        return AthenaEvent(id, 1, AthenaEventType.CURRENT_WEATHER, occurredAt, observedAt, region, weather.conditions,
            EventSeverity.INFO, null, EventProvenance(source, observedAt,
                setOf(TrustEvidence.HTTPS_SOURCE, TrustEvidence.SCHEMA_VALIDATED, TrustEvidence.RECENT_OBSERVATION)), expiresAt)
    }
}
