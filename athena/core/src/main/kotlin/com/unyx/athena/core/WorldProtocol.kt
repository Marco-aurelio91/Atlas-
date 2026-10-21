package com.unyx.athena.core

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** V2 adds domains without changing the deployed weather-only V1 contract. */
const val WORLD_PROTOCOL_VERSION = 2

sealed interface WorldSelector {
    data class Weather(val region: GeographicRegion) : WorldSelector
    data class Service(val id: String) : WorldSelector {
        init { require(id.matches(Regex("[a-z0-9.-]{1,64}"))) }
    }
}

enum class ServiceCondition { OPERATIONAL, DEGRADED, MAJOR_OUTAGE, CRITICAL_OUTAGE }
sealed interface WorldPayload {
    data class Weather(val conditions: WeatherConditions) : WorldPayload
    data class Service(val condition: ServiceCondition) : WorldPayload
    /** Two distinct recent sub-zero observations; not a forecast or official warning. */
    data class SustainedCold(val ruleVersion: Int = 1) : WorldPayload
}

data class WorldCandidate(val selector: WorldSelector, val payload: WorldPayload, val occurredAt: Instant)
data class WorldEvidence(val eventId: String, val sourceId: String, val occurredAt: Instant, val expiresAt: Instant)
data class WorldEvent(
    val id: String,
    val selector: WorldSelector,
    val payload: WorldPayload,
    val source: AthenaSource,
    val occurredAt: Instant,
    val observedAt: Instant,
    val expiresAt: Instant,
    val evidence: List<WorldEvidence> = emptyList(),
    val schemaVersion: Int = WORLD_PROTOCOL_VERSION,
) {
    val confidence: Float? get() = null
    fun fresh(now: Instant, maxAge: Duration = Duration.ofHours(1)) =
        schemaVersion == WORLD_PROTOCOL_VERSION && !now.isBefore(observedAt) && now.isBefore(expiresAt) &&
            Duration.between(observedAt, now) <= maxAge
}

data class WorldRequest(
    val selector: WorldSelector,
    val maxAge: Duration = Duration.ofMinutes(15),
    val deadline: Duration = Duration.ofSeconds(10),
    val version: Int = WORLD_PROTOCOL_VERSION,
) {
    init {
        require(!maxAge.isNegative && maxAge <= Duration.ofHours(1))
        require(deadline >= Duration.ofMillis(1) && deadline <= Duration.ofSeconds(30))
    }
}
sealed interface WorldReply {
    data class Success(val event: WorldEvent, val fromCache: Boolean) : WorldReply
    data class Failure(val error: AthenaError) : WorldReply
}

data class WorldManifest(val source: AthenaSource, val retention: Duration, val selectors: Set<WorldSelector> = emptySet())
interface WorldConnector {
    val descriptor: WorldManifest
    fun accepts(selector: WorldSelector): Boolean
    suspend fun fetch(selector: WorldSelector): WorldCandidate
}
enum class CircuitState { CLOSED, OPEN, HALF_OPEN }
data class WorldSourceHealth(
    val state: SourceHealthState = SourceHealthState.HEALTHY,
    val circuit: CircuitState = CircuitState.CLOSED,
    val failures: Int = 0,
    val attempts: Long = 0,
    val latencyMillis: Long? = null,
    val retryAt: Instant? = null,
    val error: AthenaError? = null,
    val lastSuccessAt: Instant? = null,
    val lastFailureAt: Instant? = null,
)
data class WorldDiagnostics(
    val cacheHits: Long = 0, val cacheMisses: Long = 0, val rejected: Long = 0,
    val changes: Long = 0, val duplicates: Long = 0, val storageFailures: Long = 0,
)

/** Snapshot is authoritative; changes is a lossy notification stream without replay. */
interface WorldProtocol {
    val state: StateFlow<List<WorldEvent>>
    val changes: SharedFlow<WorldEvent>
    val health: StateFlow<Map<String, WorldSourceHealth>>
    val diagnostics: StateFlow<WorldDiagnostics>
    suspend fun query(request: WorldRequest): WorldReply
    suspend fun maintain()
}

data class WorldSubscription(val id: String, val selector: WorldSelector, val expiresAt: Instant) {
    init { require(id.matches(Regex("[a-z0-9-]{1,64}"))) }
}
/** Only the latest bounded world state and expiring selectors, never personal context. */
data class WorldSnapshot(val events: List<WorldEvent> = emptyList(), val subscriptions: List<WorldSubscription> = emptyList())
interface WorldStore {
    fun load(): WorldSnapshot
    /** Commit the entire snapshot atomically or throw, leaving the previous one readable. */
    fun save(snapshot: WorldSnapshot)
}
