package com.unyx.athena.runtime

import com.unyx.athena.core.*
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/** Validation and deterministic normalization occur before cache, deduplication or publication. */
class EventGateway {
    fun accept(
        manifest: ConnectorManifest,
        query: WorldQuery,
        observation: WeatherObservation,
        now: Instant,
    ): AthenaEvent {
        val conditions = observation.conditions
        if (observation.region != query.region ||
            !conditions.temperatureC.isFinite() || conditions.temperatureC !in -100.0..70.0 ||
            conditions.weatherCode !in WMO_CODES ||
            manifest.source.host !in manifest.networkHosts ||
            query.domain !in manifest.domains || ConnectorCapability.QUERY !in manifest.capabilities
        ) throw ConnectorException(AthenaError.INVALID_DATA)

        if (observation.occurredAt.isAfter(now.plusSeconds(300))) {
            throw ConnectorException(AthenaError.INVALID_DATA)
        }
        val expiresAt = minOf(now.plus(manifest.retention), observation.occurredAt.plus(MAX_OBSERVATION_AGE))
        if (!expiresAt.isAfter(now)) throw ConnectorException(AthenaError.STALE_DATA)

        // Retrieval time is intentionally excluded: polling the same observation is not a new event.
        val canonical = listOf(
            manifest.source.connectorId, query.region.latitudeHundredths, query.region.longitudeHundredths,
            observation.occurredAt, java.lang.Double.toHexString(conditions.temperatureC), conditions.weatherCode,
        ).joinToString("|")
        val id = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return AthenaEvent(
            id = id,
            schemaVersion = 1,
            type = AthenaEventType.CURRENT_WEATHER,
            occurredAt = observation.occurredAt,
            observedAt = now,
            region = query.region,
            weather = conditions,
            // Current WMO conditions alone are not an official emergency alert.
            severity = EventSeverity.INFO,
            confidence = null,
            provenance = EventProvenance(
                manifest.source, now,
                setOf(TrustEvidence.HTTPS_SOURCE, TrustEvidence.SCHEMA_VALIDATED, TrustEvidence.RECENT_OBSERVATION),
            ),
            expiresAt = expiresAt,
        )
    }

    companion object {
        val MAX_OBSERVATION_AGE: Duration = Duration.ofHours(1)
        private val WMO_CODES = setOf(0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65,
            66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99)
    }
}
