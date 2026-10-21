package com.unyx.athena.runtime

import com.unyx.athena.core.*
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ResiliencePolicy(
    val attempts: Int = 3, val failureThreshold: Int = 3,
    val cooldown: Duration = Duration.ofMinutes(1), val baseDelayMillis: Long = 250,
) {
    init { require(attempts in 1..4 && failureThreshold in 1..10 && cooldown in Duration.ofSeconds(1)..Duration.ofHours(1) && baseDelayMillis in 1..2000) }
}

class MemoryWorldStore : WorldStore {
    private var snapshot = WorldSnapshot()
    @Synchronized override fun load() = snapshot
    @Synchronized override fun save(snapshot: WorldSnapshot) { this.snapshot = snapshot }
}

class WorldRuntime(
    private val connectors: List<WorldConnector>,
    private val store: WorldStore = MemoryWorldStore(),
    private val clock: Clock = Clock.systemUTC(),
    private val policy: ResiliencePolicy = ResiliencePolicy(),
    private val jitter: (Long) -> Long = { Random.nextLong(it + 1) },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : WorldProtocol {
    init {
        require(connectors.isNotEmpty())
        require(connectors.map { it.descriptor.source.connectorId }.distinct().size == connectors.size)
        connectors.forEach {
            require(it.descriptor.source.connectorId.matches(Regex("[a-z0-9.-]{1,64}")))
            require(it.descriptor.retention in Duration.ofSeconds(1)..Duration.ofHours(1))
        }
    }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val stateLock = Mutex()
    private val mutableState = MutableStateFlow<List<WorldEvent>>(emptyList())
    override val state = mutableState.asStateFlow()
    private val mutableChanges = MutableSharedFlow<WorldEvent>(0, 64, BufferOverflow.DROP_OLDEST)
    override val changes = mutableChanges.asSharedFlow()
    private val mutableHealth = MutableStateFlow<Map<String, WorldSourceHealth>>(emptyMap())
    override val health = mutableHealth.asStateFlow()
    private val mutableDiagnostics = MutableStateFlow(WorldDiagnostics())
    override val diagnostics = mutableDiagnostics.asStateFlow()
    private val mutableSubscriptions = MutableStateFlow<List<WorldSubscription>>(emptyList())
    val subscriptions = mutableSubscriptions.asStateFlow()
    private var loaded = false
    private val gateway = WorldGateway()

    override suspend fun maintain() = stateLock.withLock { restoreAndPrune() }

    private fun restoreAndPrune() {
        val now = clock.instant()
        val initial = !loaded
        if (!loaded) {
            try {
                val restored = store.load()
                mutableState.value = restored.events.take(32).filter { validRestored(it, now) }
                mutableSubscriptions.value = restored.subscriptions.take(16).filter {
                    it.expiresAt > now && it.expiresAt <= now.plusSeconds(86400) && sourceFor(it.selector) != null
                }
            } catch (_: Exception) { storageFailure() }
            loaded = true
        }
        val events = mutableState.value.filter { it.fresh(now) }
        val subs = mutableSubscriptions.value.filter { it.expiresAt > now }
        if (initial || events != mutableState.value || subs != mutableSubscriptions.value) {
            try { commit(events, subs) } catch (_: ConnectorException) {
                // Expiry must still hide data if disk is full; restored data is revalidated next boot.
                mutableState.value = events
                mutableSubscriptions.value = subs
            }
        }
    }

    private fun validRestored(event: WorldEvent, now: Instant): Boolean {
        if (!event.fresh(now) || event.payload is WorldPayload.SustainedCold) return false
        val source = sourceFor(event.selector) ?: return false
        if (source.descriptor.source != event.source) return false
        return try {
            val accepted = gateway.accept(source, event.selector, WorldCandidate(event.selector, event.payload, event.occurredAt), event.observedAt)
            accepted.id == event.id && event.expiresAt <= accepted.expiresAt && event.evidence.isEmpty()
        } catch (_: Exception) { false }
    }

    private fun sourceFor(selector: WorldSelector) = connectors.singleOrNull { it.accepts(selector) }

    override suspend fun query(request: WorldRequest): WorldReply {
        if (request.version != WORLD_PROTOCOL_VERSION) return WorldReply.Failure(AthenaError.UNSUPPORTED_VERSION)
        val connector = sourceFor(request.selector) ?: return WorldReply.Failure(AthenaError.INVALID_DATA)
        val id = connector.descriptor.source.connectorId
        return withTimeoutOrNull(request.deadline.toMillis()) {
            locks.getOrPut(id) { Mutex() }.withLock {
                maintain()
                val cached = state.value.firstOrNull { it.selector == request.selector && it.payload !is WorldPayload.SustainedCold }
                if (!request.maxAge.isZero && cached?.fresh(clock.instant(), request.maxAge) == true) {
                    mutableDiagnostics.update { it.copy(cacheHits = it.cacheHits + 1) }
                    return@withLock WorldReply.Success(cached, true)
                }
                mutableDiagnostics.update { it.copy(cacheMisses = it.cacheMisses + 1) }
                val h = health.value[id] ?: WorldSourceHealth()
                if (h.retryAt?.isAfter(clock.instant()) == true) return@withLock WorldReply.Failure(h.error ?: AthenaError.UNAVAILABLE)
                if (h.circuit == CircuitState.OPEN) updateHealth(id) { it.copy(circuit = CircuitState.HALF_OPEN) }
                val start = System.nanoTime()
                try {
                    val candidate = fetch(connector, request.selector)
                    val event = gateway.accept(connector, request.selector, candidate, clock.instant())
                    stateLock.withLock {
                        val old = state.value.firstOrNull { it.selector == request.selector && it.payload !is WorldPayload.SustainedCold }
                        if (old != null && old.occurredAt > event.occurredAt) throw ConnectorException(AthenaError.STALE_DATA)
                        val derived = ColdCorrelation.derive(old, event, clock.instant())
                        val remaining = state.value.filter { it.selector != request.selector && it.fresh(clock.instant()) &&
                            !(request.selector is WorldSelector.Weather && it.selector is WorldSelector.Weather) }
                        commit((remaining + listOfNotNull(event, derived)).takeLast(32), subscriptions.value)
                        val changed = old == null || old.payload != event.payload
                        mutableDiagnostics.update { if (changed) it.copy(changes = it.changes + 1) else it.copy(duplicates = it.duplicates + 1) }
                        if (changed) mutableChanges.tryEmit(event)
                        derived?.let { mutableChanges.tryEmit(it) }
                    }
                    updateHealth(id) { it.copy(state = SourceHealthState.HEALTHY, circuit = CircuitState.CLOSED, failures = 0,
                        latencyMillis = (System.nanoTime() - start) / 1_000_000, retryAt = null, error = null, lastSuccessAt = clock.instant()) }
                    WorldReply.Success(event, false)
                } catch (e: TimeoutCancellationException) {
                    failure(id, AthenaError.TIMEOUT, null, start)
                    throw e
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    val error = (e as? ConnectorException)?.error ?: AthenaError.UNAVAILABLE
                    if (error == AthenaError.INVALID_DATA || error == AthenaError.STALE_DATA) mutableDiagnostics.update { it.copy(rejected = it.rejected + 1) }
                    failure(id, error, (e as? ConnectorException)?.retryAfter, start)
                    WorldReply.Failure(error)
                }
            }
        } ?: WorldReply.Failure(AthenaError.TIMEOUT)
    }

    private suspend fun fetch(connector: WorldConnector, selector: WorldSelector): WorldCandidate {
        val id = connector.descriptor.source.connectorId
        val attempts = if (health.value[id]?.circuit == CircuitState.HALF_OPEN) 1 else policy.attempts
        repeat(attempts) { attempt ->
            updateHealth(id) { it.copy(attempts = it.attempts + 1) }
            try { return connector.fetch(selector) }
            catch (e: CancellationException) { throw e }
            catch (e: ConnectorException) {
                if (attempt == attempts - 1 || e.error !in setOf(AthenaError.UNAVAILABLE, AthenaError.TIMEOUT)) throw e
                val base = policy.baseDelayMillis * (1L shl attempt)
                sleep(base + jitter(base).coerceIn(0, base))
            }
        }
        throw ConnectorException(AthenaError.UNAVAILABLE)
    }

    private fun failure(id: String, error: AthenaError, retryAfter: Duration?, start: Long) = updateHealth(id) {
        val count = it.failures + 1
        val open = count >= policy.failureThreshold || it.circuit == CircuitState.HALF_OPEN
        val wait = (retryAfter ?: policy.cooldown).coerceIn(Duration.ofSeconds(1), Duration.ofHours(1))
        it.copy(state = when (error) {
            AthenaError.RATE_LIMITED -> SourceHealthState.RATE_LIMITED
            AthenaError.INVALID_DATA, AthenaError.STALE_DATA -> SourceHealthState.DEGRADED
            else -> SourceHealthState.UNAVAILABLE
        }, circuit = if (open) CircuitState.OPEN else CircuitState.CLOSED, failures = count,
            retryAt = if (open || error == AthenaError.RATE_LIMITED) clock.instant().plus(wait) else null,
            latencyMillis = (System.nanoTime() - start) / 1_000_000, error = error, lastFailureAt = clock.instant())
    }

    private fun updateHealth(id: String, transform: (WorldSourceHealth) -> WorldSourceHealth) =
        mutableHealth.update { it + (id to transform(it[id] ?: WorldSourceHealth())) }
    private fun storageFailure() = mutableDiagnostics.update { it.copy(storageFailures = it.storageFailures + 1) }
    private fun commit(events: List<WorldEvent>, subs: List<WorldSubscription>) {
        try { store.save(WorldSnapshot(events, subs)) } catch (e: Exception) { storageFailure(); throw ConnectorException(AthenaError.UNAVAILABLE) }
        mutableState.value = events
        mutableSubscriptions.value = subs
    }

    suspend fun subscribe(subscription: WorldSubscription) = stateLock.withLock {
        restoreAndPrune()
        require(sourceFor(subscription.selector) != null)
        require(subscription.expiresAt > clock.instant() && subscription.expiresAt <= clock.instant().plusSeconds(86400))
        val next = subscriptions.value.filter { it.id != subscription.id } + subscription
        require(next.size <= 16)
        commit(state.value, next)
    }
    suspend fun cancel(id: String) = stateLock.withLock {
        restoreAndPrune()
        commit(state.value, subscriptions.value.filter { it.id != id })
    }
    suspend fun cancelAll() = stateLock.withLock { restoreAndPrune(); commit(state.value, emptyList()) }

    /** Worker calls once per pass. Grouping prevents identical selectors multiplying traffic. */
    suspend fun poll(): List<WorldReply> {
        maintain()
        val selectors = subscriptions.value.map { it.selector }.distinct()
        return coroutineScope { selectors.map { selector -> async {
            if (subscriptions.value.none { it.selector == selector && it.expiresAt > clock.instant() }) null
            else query(WorldRequest(selector)).takeIf {
                subscriptions.value.any { it.selector == selector && it.expiresAt > clock.instant() }
            }
        } }.awaitAll().filterNotNull() }
    }
}
