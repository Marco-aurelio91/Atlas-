package com.unyx.atlas.data.ai

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

/** One day of AI usage measured on this device, as reported by the provider's own `usage` fields. */
data class AiDayUsage(
    val date: String,
    val requests: Int,
    val inputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val outputTokens: Long,
)

/**
 * Estimated cost of [AiDayUsage], at the public per-million-token rates of the model the gateway
 * is configured with (claude-sonnet-5: $2 input, $10 output, cache reads $0.20, 5-minute cache
 * writes 1.25x input — price table checked 2026-10-06). It is an estimate to size the free/paid
 * plans with real numbers, not a bill: re-check the rates if the gateway's model changes.
 */
object AiUsageCost {
    private const val INPUT_PER_MTOK = 2.00
    private const val OUTPUT_PER_MTOK = 10.00
    private const val CACHE_READ_PER_MTOK = 0.20
    private const val CACHE_WRITE_PER_MTOK = 2.50

    fun estimateUsd(day: AiDayUsage): Double =
        (day.inputTokens * INPUT_PER_MTOK +
            day.outputTokens * OUTPUT_PER_MTOK +
            day.cacheReadTokens * CACHE_READ_PER_MTOK +
            day.cacheWriteTokens * CACHE_WRITE_PER_MTOK) / 1_000_000.0

    /** Average cost of one gateway call, or null with no data. */
    fun averageUsdPerRequest(days: List<AiDayUsage>): Double? {
        val requests = days.sumOf { it.requests }
        if (requests == 0) return null
        return days.sumOf { estimateUsd(it) } / requests
    }
}

/**
 * Keeps per-day token totals for the Claude calls this device made, so the real cost per message
 * can be measured instead of guessed (the gateway only counts calls). Stores numbers only — never
 * prompts or replies — in plain app-private preferences, and keeps 30 days.
 */
class AiUsageMeter(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    @Synchronized
    fun record(
        inputTokens: Long,
        cacheReadTokens: Long,
        cacheWriteTokens: Long,
        outputTokens: Long,
        today: LocalDate = LocalDate.now(),
    ) {
        if (inputTokens + cacheReadTokens + cacheWriteTokens + outputTokens <= 0L) return
        val key = KEY_PREFIX + today
        val current = parse(today.toString(), prefs.getString(key, null))
        val updated = AiDayUsage(
            date = current.date,
            requests = current.requests + 1,
            inputTokens = current.inputTokens + inputTokens,
            cacheReadTokens = current.cacheReadTokens + cacheReadTokens,
            cacheWriteTokens = current.cacheWriteTokens + cacheWriteTokens,
            outputTokens = current.outputTokens + outputTokens,
        )
        val edit = prefs.edit().putString(key, format(updated))
        val cutoff = KEY_PREFIX + today.minusDays(RETENTION_DAYS)
        prefs.all.keys.filter { it.startsWith(KEY_PREFIX) && it < cutoff }.forEach { edit.remove(it) }
        edit.apply()
    }

    /** The last [days] days that have data, newest first. */
    @Synchronized
    fun recent(days: Int = 7, today: LocalDate = LocalDate.now()): List<AiDayUsage> =
        (0 until days).mapNotNull { offset ->
            val date = today.minusDays(offset.toLong()).toString()
            prefs.getString(KEY_PREFIX + date, null)?.let { parse(date, it) }?.takeIf { it.requests > 0 }
        }

    private fun parse(date: String, raw: String?): AiDayUsage {
        val parts = raw?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()
        if (parts.size != 5) return AiDayUsage(date, 0, 0, 0, 0, 0)
        return AiDayUsage(date, parts[0].toInt(), parts[1], parts[2], parts[3], parts[4])
    }

    private fun format(day: AiDayUsage) =
        listOf(day.requests.toLong(), day.inputTokens, day.cacheReadTokens, day.cacheWriteTokens, day.outputTokens)
            .joinToString(",")

    private companion object {
        const val PREFS = "atlas_ai_usage_meter"
        const val KEY_PREFIX = "day:"
        const val RETENTION_DAYS = 30L
    }
}
