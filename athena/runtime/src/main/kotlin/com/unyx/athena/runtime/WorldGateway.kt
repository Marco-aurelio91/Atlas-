package com.unyx.athena.runtime

import com.unyx.athena.core.*
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

class WorldGateway {
    fun accept(connector: WorldConnector, selector: WorldSelector, candidate: WorldCandidate, now: Instant): WorldEvent {
        val manifest = connector.descriptor
        if (!connector.accepts(selector) || candidate.selector != selector ||
            candidate.occurredAt > now.plusSeconds(300)) invalid()
        val ttl = manifest.retention
        if (ttl <= Duration.ZERO || ttl > Duration.ofHours(1)) invalid()
        val payload = candidate.payload
        val expires = when {
            selector is WorldSelector.Weather && payload is WorldPayload.Weather -> {
                val c = payload.conditions
                if (!c.temperatureC.isFinite() || c.temperatureC !in -100.0..70.0 || c.weatherCode !in codes) invalid()
                minOf(now.plus(ttl), candidate.occurredAt.plusSeconds(3600))
            }
            selector is WorldSelector.Service && payload is WorldPayload.Service -> now.plus(ttl)
            else -> invalid()
        }
        // A status page can remain unchanged for months. Its updatedAt orders revisions;
        // freshness comes from the successful retrieval, not the last status transition.
        if (expires <= now) throw ConnectorException(AthenaError.STALE_DATA)
        val canonical = "${manifest.source.connectorId}|$selector|${candidate.occurredAt}|$payload"
        return WorldEvent(hash(canonical), selector, payload, manifest.source, candidate.occurredAt, now, expires)
    }

    companion object {
        private val codes = setOf(0,1,2,3,45,48,51,53,55,56,57,61,63,65,66,67,71,73,75,77,80,81,82,85,86,95,96,99)
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun invalid(): Nothing = throw ConnectorException(AthenaError.INVALID_DATA)
    }
}

/** Evidence is from successive observations, never misrepresented as independent sources. */
object ColdCorrelation {
    fun derive(previous: WorldEvent?, current: WorldEvent, now: Instant): WorldEvent? {
        val a = previous ?: return null
        val first = (a.payload as? WorldPayload.Weather)?.conditions ?: return null
        val second = (current.payload as? WorldPayload.Weather)?.conditions ?: return null
        if (a.selector != current.selector || a.source != current.source || a.id == current.id ||
            !a.fresh(now) || !current.fresh(now) || first.temperatureC > 0 || second.temperatureC > 0 ||
            Duration.between(a.occurredAt, current.occurredAt) !in Duration.ofMinutes(10)..Duration.ofMinutes(45)) return null
        val evidence = listOf(a, current).map { WorldEvidence(it.id, it.source.connectorId, it.occurredAt, it.expiresAt) }
        return current.copy(
            id = WorldGateway.hash("sustained-cold-v1|${a.id}|${current.id}"),
            payload = WorldPayload.SustainedCold(), expiresAt = minOf(a.expiresAt, current.expiresAt), evidence = evidence,
        )
    }
}
