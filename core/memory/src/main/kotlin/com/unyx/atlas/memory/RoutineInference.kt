package com.unyx.atlas.memory

import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Turns a burst of raw [MemoryEvent] rows for one `evento` key into an inferred [MemoryRoutine] —
 * the pattern-detection half of "Rotinas" (architecture spec). Pure function, no I/O: callers
 * supply the events (see [MemoryRepository.logEventAndInferRoutine]) and persist whatever comes
 * back.
 */
object RoutineInference {
    /** Below this many occurrences there isn't enough signal to call it a pattern — a routine
     * inferred from 1-2 events would be noise, not a habit. */
    private const val MIN_OCCURRENCES = 3

    /** A weekday only counts as part of the routine once it's shown up more than once — otherwise
     * a single Tuesday occurrence would claim "toda terça" from one data point. */
    private const val MIN_OCCURRENCES_PER_WEEKDAY = 2

    /** Returns null when there isn't yet enough signal to infer anything — the caller should leave
     * any existing routine as-is rather than overwrite it with a weaker one. */
    fun infer(evento: String, events: List<MemoryEvent>): MemoryRoutine? {
        val zoned = events
            .mapNotNull { it.createdAt }
            .mapNotNull { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
            .map { it.atZoneSameInstant(ZoneId.systemDefault()) }
        if (zoned.size < MIN_OCCURRENCES) return null

        val avgSecondOfDay = zoned.map { it.toLocalTime().toSecondOfDay() }.average().toInt()
        val horarioMedio = LocalTime.ofSecondOfDay(avgSecondOfDay.toLong())
            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))

        val dias = zoned.groupingBy { it.dayOfWeek.value }.eachCount()
            .filterValues { it >= MIN_OCCURRENCES_PER_WEEKDAY }
            .keys
            .sorted()

        val distinctDates = zoned.map { it.toLocalDate() }.distinct()
        val firstDate = distinctDates.minOrNull()
        val lastDate = distinctDates.maxOrNull()
        val spanDays = if (firstDate != null && lastDate != null) {
            (ChronoUnit.DAYS.between(firstDate, lastDate) + 1).coerceAtLeast(1)
        } else 1
        val frequencia = (distinctDates.size.toFloat() / spanDays.toFloat()).coerceIn(0f, 1f)

        // More occurrences = more confidence this isn't a fluke; caps out once the pattern is well
        // established (10+ occurrences), floors at 0.3 since MIN_OCCURRENCES already filtered out
        // anything weaker than that.
        val confidence = (zoned.size / 10f).coerceIn(0.3f, 1f)

        return MemoryRoutine(
            evento = evento,
            horarioMedio = horarioMedio,
            frequencia = frequencia,
            dias = dias,
            confidence = confidence,
            importance = if (frequencia >= 0.5f) 0.6f else 0.4f,
        )
    }
}
