package com.unyx.athena.core

import java.time.Duration
import java.time.Instant

/** A coarse public query region, never a device Location or a person's home/work. */
data class GeographicRegion(val latitudeHundredths: Int, val longitudeHundredths: Int) {
    init {
        require(latitudeHundredths in -9000..9000)
        require(longitudeHundredths in -18000..18000)
    }
    val latitude: Double get() = latitudeHundredths / 100.0
    val longitude: Double get() = longitudeHundredths / 100.0
}

enum class WorldDomain { WEATHER }
enum class ConnectorCapability { QUERY }
enum class EventSeverity { INFO, LOW, MODERATE, HIGH, CRITICAL }
enum class AthenaEventType { CURRENT_WEATHER }
enum class TrustEvidence { HTTPS_SOURCE, SCHEMA_VALIDATED, RECENT_OBSERVATION }

data class AthenaSource(val connectorId: String, val provider: String, val host: String)

data class ConnectorManifest(
    val source: AthenaSource,
    val version: Int,
    val domains: Set<WorldDomain>,
    val capabilities: Set<ConnectorCapability>,
    val networkHosts: Set<String>,
    val retention: Duration,
)

/** Typed payload: external text has no place to request personal tools or carry credentials. */
data class WeatherConditions(val temperatureC: Double, val weatherCode: Int)

data class EventProvenance(
    val source: AthenaSource,
    val retrievedAt: Instant,
    val evidence: Set<TrustEvidence>,
)

data class AthenaEvent(
    val id: String,
    val schemaVersion: Int,
    val type: AthenaEventType,
    val occurredAt: Instant,
    val observedAt: Instant,
    val region: GeographicRegion,
    val weather: WeatherConditions,
    val severity: EventSeverity,
    /** Unknown until calibrated against evidence; HTTPS alone is not a probability of truth. */
    val confidence: Float?,
    val provenance: EventProvenance,
    val expiresAt: Instant,
) {
    fun isFresh(now: Instant, maxAge: Duration): Boolean =
        !maxAge.isNegative && !now.isBefore(observedAt) && now.isBefore(expiresAt) &&
            Duration.between(observedAt, now) <= maxAge
}

/** Connector output is a candidate, not yet a trusted/published AthenaEvent. */
data class WeatherObservation(
    val region: GeographicRegion,
    val conditions: WeatherConditions,
    val occurredAt: Instant,
)

data class WorldQuery(
    val region: GeographicRegion,
    val maxAge: Duration = Duration.ofMinutes(30),
    val deadline: Duration = Duration.ofSeconds(10),
) {
    init {
        require(!maxAge.isNegative && maxAge <= Duration.ofHours(1))
        require(deadline >= Duration.ofMillis(1) && deadline <= Duration.ofSeconds(30))
    }
    val domain: WorldDomain get() = WorldDomain.WEATHER
}

enum class AthenaError { TIMEOUT, UNAVAILABLE, RATE_LIMITED, INVALID_DATA, STALE_DATA, UNSUPPORTED_VERSION }

sealed interface WorldQueryResult {
    data class Success(val event: AthenaEvent, val fromCache: Boolean) : WorldQueryResult
    data class Failure(val error: AthenaError) : WorldQueryResult
}

enum class SourceHealthState { HEALTHY, UNAVAILABLE, RATE_LIMITED, DEGRADED }

data class SourceHealth(
    val connectorId: String,
    val state: SourceHealthState,
    val lastSuccessAt: Instant?,
    val lastFailureAt: Instant?,
    val consecutiveFailures: Int,
    val error: AthenaError?,
)

/** Exception messages deliberately omit response bodies, query regions and provider URLs. */
class ConnectorException(val error: AthenaError, val retryAfter: Duration? = null) : Exception(error.name)
