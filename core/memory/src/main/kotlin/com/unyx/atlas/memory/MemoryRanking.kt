package com.unyx.atlas.memory

import java.time.Duration
import java.time.OffsetDateTime
import kotlin.math.exp
import kotlin.math.ln

/**
 * Combines a memory record's static trust signals (importance, confidence, whether the user
 * confirmed it) with two real usage signals — how recently it actually informed a response, and
 * how many times total ([observationCount]) — into one score used to decide what earns a spot in
 * a request's context — see `buildMemoryDigest` in feature:chat, which used to just sort by a
 * single field (importance or confidence alone).
 *
 * Computed fresh on every read rather than persisted as a decaying `heat` column: this codebase has
 * no scheduled-job infrastructure to keep a stored decay accurate as time passes with the app
 * closed, and a function of already-stored timestamps decays itself for free on every read instead.
 * [MemoryRepository.markUsed] is what feeds `last_used_at`, and [MemoryRepository.accessCounts]
 * (reading `markUsed`'s own log back) is what feeds [observationCount] — neither existed before.
 *
 * [observationCount] defaults to 0 (the caller didn't fetch it) rather than being required, so
 * existing call sites that only care about the other signals keep compiling unchanged.
 */
fun MemoryMeta.weightedScore(now: OffsetDateTime = OffsetDateTime.now(), observationCount: Int = 0): Float {
    val recency = recencyScore(lastUsedAt ?: createdAt, now)
    val confirmedBoost = if (confirmedByUser) 0.1f else 0f
    // Logarithmic, not linear — the first few repeats matter a lot more than the fortieth one, and
    // capping at 0.1 keeps a heavily-reused item from drowning out importance/confidence entirely.
    val observationBoost = (ln(1f + observationCount) / ln(10f)).coerceIn(0f, 1f) * 0.1f
    return (importance * 0.4f) + (confidence * 0.2f) + (recency * 0.3f) + confirmedBoost + observationBoost
}

/** 1.0 for something used/created just now, decaying toward 0 with a ~14-day half-life — recent
 * enough to matter without letting a single recent touch dominate forever. A record with no parsable
 * timestamp (shouldn't happen — created_at is always set by Postgres) gets the lowest recency rather
 * than crashing the whole ranking. */
private fun recencyScore(timestamp: String?, now: OffsetDateTime): Float {
    val parsed = timestamp?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: return 0f
    val ageDays = Duration.between(parsed, now).toMinutes() / (60.0 * 24.0)
    if (ageDays <= 0) return 1f
    val halfLifeDays = 14.0
    return exp(-ageDays * ln(2.0) / halfLifeDays).toFloat().coerceIn(0f, 1f)
}
