package com.unyx.athena.core

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

const val ATHENA_PROTOCOL_VERSION = 1

enum class AthenaMessageType { QUERY_REQUEST, QUERY_RESULT }

/** Versioned in-process contract. No cloud transport/serialization is implied by this envelope. */
data class AtlasAthenaEnvelope<T>(
    val protocolVersion: Int,
    val messageId: String,
    val type: AthenaMessageType,
    val createdAt: Instant,
    val correlationId: String?,
    val payload: T,
)

interface AthenaConnector {
    val manifest: ConnectorManifest
    suspend fun query(query: WorldQuery): WeatherObservation
}

interface AthenaEventBus {
    /** Live, best-effort notifications. Query the bounded world cache for current state. */
    fun observe(): Flow<AthenaEvent>
}

interface AtlasAthenaProtocol {
    val events: AthenaEventBus
    val sourceHealth: StateFlow<SourceHealth?>
    suspend fun query(request: AtlasAthenaEnvelope<WorldQuery>): AtlasAthenaEnvelope<WorldQueryResult>
}
