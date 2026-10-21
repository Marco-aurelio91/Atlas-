package com.unyx.atlas.athena

import com.unyx.athena.core.*
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** ATLAS-owned boundary. Accepts a coarse region, never user identity, device state or memory. */
class AtlasAthenaBridge(
    private val protocol: AtlasAthenaProtocol,
    private val clock: Clock = Clock.systemUTC(),
) {
    val events: AthenaEventBus get() = protocol.events
    val sourceHealth get() = protocol.sourceHealth

    suspend fun weather(
        region: GeographicRegion,
        maxAge: Duration = Duration.ofMinutes(30),
        deadline: Duration = Duration.ofSeconds(10),
    ): WorldQueryResult {
        val request = AtlasAthenaEnvelope(
            ATHENA_PROTOCOL_VERSION, UUID.randomUUID().toString(), AthenaMessageType.QUERY_REQUEST,
            clock.instant(), null, WorldQuery(region, maxAge, deadline),
        )
        val response = protocol.query(request)
        if (response.protocolVersion != ATHENA_PROTOCOL_VERSION) {
            return WorldQueryResult.Failure(AthenaError.UNSUPPORTED_VERSION)
        }
        if (response.correlationId != request.messageId || response.type != AthenaMessageType.QUERY_RESULT) {
            return WorldQueryResult.Failure(AthenaError.INVALID_DATA)
        }
        val result = response.payload
        if (result is WorldQueryResult.Success) {
            if (result.event.region != region || result.event.schemaVersion != 1) {
                return WorldQueryResult.Failure(AthenaError.INVALID_DATA)
            }
            // maxAge=0 means bypass cache, not a requirement for zero transport latency.
            val allowedAge = if (maxAge.isZero && !result.fromCache) Duration.ofHours(1) else maxAge
            if (!result.event.isFresh(clock.instant(), allowedAge)) {
                return WorldQueryResult.Failure(AthenaError.STALE_DATA)
            }
        }
        return result
    }
}
