package com.unyx.athena.runtime

import com.unyx.athena.core.*
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class AthenaMetrics(
    val received: Long = 0,
    val rejected: Long = 0,
    val deduplicated: Long = 0,
    val published: Long = 0,
    val cacheHits: Long = 0,
    val failures: Long = 0,
)

/**
 * First device runtime: one query connector, no personal repositories and no disk persistence.
 * Requests are serialized (including cache fills); the deadline also covers waiting for that lock.
 * Cache and dedup entries are bounded and lazily removed at expiry. No query history is retained.
 */
class AthenaRuntime(
    private val connector: AthenaConnector,
    private val clock: Clock = Clock.systemUTC(),
    private val gateway: EventGateway = EventGateway(),
) : AtlasAthenaProtocol {
    init {
        require(connector.manifest.retention > Duration.ZERO && connector.manifest.retention <= Duration.ofHours(1))
        require(connector.manifest.source.connectorId.matches(Regex("[a-z0-9.-]{1,64}")))
    }

    private val mutex = Mutex()
    private val cache = LinkedHashMap<GeographicRegion, AthenaEvent>()
    private val seen = LinkedHashMap<String, Instant>()
    private val liveEvents = MutableSharedFlow<AthenaEvent>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events = object : AthenaEventBus {
        override fun observe() = liveEvents.asSharedFlow()
    }
    private val healthState = MutableStateFlow<SourceHealth?>(null)
    override val sourceHealth = healthState.asStateFlow()
    private val metricState = MutableStateFlow(AthenaMetrics())
    val metrics = metricState.asStateFlow()

    override suspend fun query(request: AtlasAthenaEnvelope<WorldQuery>): AtlasAthenaEnvelope<WorldQueryResult> {
        val result = when {
            request.protocolVersion != ATHENA_PROTOCOL_VERSION -> WorldQueryResult.Failure(AthenaError.UNSUPPORTED_VERSION)
            request.type != AthenaMessageType.QUERY_REQUEST -> WorldQueryResult.Failure(AthenaError.INVALID_DATA)
            else -> withTimeoutOrNull(request.payload.deadline.toMillis()) {
                mutex.withLock { resolve(request.payload) }
            } ?: WorldQueryResult.Failure(AthenaError.TIMEOUT)
        }
        return AtlasAthenaEnvelope(
            ATHENA_PROTOCOL_VERSION, UUID.randomUUID().toString(), AthenaMessageType.QUERY_RESULT,
            clock.instant(), request.messageId, result,
        )
    }

    private suspend fun resolve(query: WorldQuery): WorldQueryResult {
        val now = clock.instant()
        cache.entries.removeAll { !it.value.isFresh(now, Duration.ofHours(1)) }
        seen.entries.removeAll { !it.value.isAfter(now) }
        cache[query.region]?.takeIf { !query.maxAge.isZero && it.isFresh(now, query.maxAge) }?.let {
            metricState.value = metricState.value.copy(cacheHits = metricState.value.cacheHits + 1)
            return WorldQueryResult.Success(it, fromCache = true)
        }
        try {
            val observation = connector.query(query)
            metricState.value = metricState.value.copy(received = metricState.value.received + 1)
            val event = try {
                gateway.accept(connector.manifest, query, observation, clock.instant())
            } catch (e: ConnectorException) {
                metricState.value = metricState.value.copy(rejected = metricState.value.rejected + 1)
                throw e
            }
            // A late provider response must never replace a newer state, even when that state
            // exceeds the caller's maxAge and therefore couldn't satisfy the cache lookup.
            if (cache[query.region]?.occurredAt?.isAfter(event.occurredAt) == true) {
                throw ConnectorException(AthenaError.STALE_DATA)
            }
            cache[query.region] = event
            while (cache.size > MAX_CACHE_REGIONS) cache.remove(cache.keys.first())
            val duplicate = seen.containsKey(event.id)
            seen[event.id] = event.expiresAt
            while (seen.size > MAX_SEEN_EVENTS) seen.remove(seen.keys.first())
            if (duplicate) {
                metricState.value = metricState.value.copy(deduplicated = metricState.value.deduplicated + 1)
            } else {
                liveEvents.tryEmit(event)
                metricState.value = metricState.value.copy(published = metricState.value.published + 1)
            }
            healthState.value = SourceHealth(
                connector.manifest.source.connectorId, SourceHealthState.HEALTHY,
                clock.instant(), healthState.value?.lastFailureAt, 0, null,
            )
            return WorldQueryResult.Success(event, fromCache = false)
        } catch (e: TimeoutCancellationException) {
            recordFailure(AthenaError.TIMEOUT)
            throw e
        } catch (e: CancellationException) {
            // Both caller cancellation and deadline cancellation propagate; don't convert them
            // into unavailable and accidentally swallow a cancelled Android lifecycle.
            throw e
        } catch (e: Exception) {
            val error = (e as? ConnectorException)?.error ?: AthenaError.UNAVAILABLE
            recordFailure(error)
            return WorldQueryResult.Failure(error)
        }
    }

    private fun recordFailure(error: AthenaError) {
        metricState.value = metricState.value.copy(failures = metricState.value.failures + 1)
        val previous = healthState.value
        healthState.value = SourceHealth(
            connector.manifest.source.connectorId,
            when (error) {
                AthenaError.RATE_LIMITED -> SourceHealthState.RATE_LIMITED
                AthenaError.INVALID_DATA, AthenaError.STALE_DATA -> SourceHealthState.DEGRADED
                else -> SourceHealthState.UNAVAILABLE
            },
            previous?.lastSuccessAt, clock.instant(), (previous?.consecutiveFailures ?: 0) + 1, error,
        )
    }

    companion object {
        private const val MAX_CACHE_REGIONS = 8
        private const val MAX_SEEN_EVENTS = 64
    }
}
