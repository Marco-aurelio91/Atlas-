package com.unyx.atlas.feature.chat.agent

import android.content.Context
import com.unyx.atlas.feature.chat.DeviceContextSnapshot
import com.unyx.atlas.feature.chat.captureDeviceContext
import java.time.DayOfWeek
import java.time.LocalDateTime

/** A coarse bucket for "what part of the day is it" — plain hour-of-day comparisons (as
 * [routineNudgeFor] already does for "is this routine's usual time close to now") stay more
 * precise for anything that needs exact timing; this is for code that only needs the rough shape
 * (morning/afternoon/evening/overnight), like a future relevance/urgency weighting. */
enum class TimeOfDay { MADRUGADA, MANHA, TARDE, NOITE }

private fun timeOfDayFor(hour: Int): TimeOfDay = when (hour) {
    in 0..5 -> TimeOfDay.MADRUGADA
    in 6..11 -> TimeOfDay.MANHA
    in 12..17 -> TimeOfDay.TARDE
    else -> TimeOfDay.NOITE
}

/**
 * A structured snapshot of "what's going on right now" — the plan's own Situation Model (section
 * 17), scoped to what this app actually gathers today: [currentDateTimeContext]/
 * [currentDeviceContext] already collect this same information, just as loose, ad-hoc strings
 * built fresh for the prompt each time. This gives the same underlying signals ([captureDeviceContext]
 * is the one shared source for the device half) a reusable shape other code can read a single field
 * from — [timeOfDay] for a coarse bucket, say — without parsing a sentence meant for Claude.
 *
 * Deliberately NOT location/activity/social-context/urgency — this app doesn't gather those signals
 * at all yet (see the plan-comparison notes); adding fields nothing can populate would just be a
 * type that lies about what Atlas actually knows.
 */
data class Situation(
    val dateTime: LocalDateTime,
    val timeOfDay: TimeOfDay,
    val dayOfWeek: DayOfWeek,
    val device: DeviceContextSnapshot,
)

fun currentSituation(appContext: Context, now: LocalDateTime = LocalDateTime.now()): Situation =
    Situation(
        dateTime = now,
        timeOfDay = timeOfDayFor(now.hour),
        dayOfWeek = now.dayOfWeek,
        device = captureDeviceContext(appContext),
    )
