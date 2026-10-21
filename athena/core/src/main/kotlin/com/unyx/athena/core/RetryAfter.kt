package com.unyx.athena.core

import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Accept delta-seconds or HTTP-date; untrusted header text never appears in diagnostics. */
fun retryAfter(value: String?, now: Instant = Instant.now()): Duration? {
    if (value == null || value.length > 128) return null
    val seconds = value.trim().toLongOrNull()
    val duration = if (seconds != null) Duration.ofSeconds(seconds.coerceIn(1, 3600)) else try {
        Duration.between(now, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
    } catch (_: Exception) { return null }
    return duration.coerceIn(Duration.ofSeconds(1), Duration.ofHours(1))
}
