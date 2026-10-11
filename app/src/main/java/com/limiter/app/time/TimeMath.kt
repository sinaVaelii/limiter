package com.limiter.app.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure time-math utilities.
 *
 * All functions accept explicit time parameters — no hidden clock access.
 */
object TimeMath {

    /**
     * Returns the epoch-millis of the next occurrence of [minuteOfDay]
     * that is **strictly after** [nowMillis] in the given [zone].
     *
     * Handles DST gaps and overlaps:
     * - If the target local time falls in a DST gap, java.time automatically
     *   adjusts it forward to the first valid instant after the gap.
     * - If it falls in a DST overlap, the earlier offset is used first;
     *   if that is not strictly after now, the later offset is tried.
     */
    fun nextOccurrence(
        nowMillis: Long,
        zone: ZoneId,
        minuteOfDay: Int
    ): Long {
        require(minuteOfDay in 0..1439) {
            "minuteOfDay must be in 0..1439, was $minuteOfDay"
        }

        val nowInstant = Instant.ofEpochMilli(nowMillis)
        val nowZoned = nowInstant.atZone(zone)
        val targetTime = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)

        // Try today first
        val todayCandidate = nowZoned.toLocalDate().atTime(targetTime).atZone(zone)
        if (todayCandidate.toInstant().toEpochMilli() > nowMillis) {
            return todayCandidate.toInstant().toEpochMilli()
        }

        // Try tomorrow
        val tomorrowCandidate = nowZoned.toLocalDate().plusDays(1).atTime(targetTime).atZone(zone)
        return tomorrowCandidate.toInstant().toEpochMilli()
    }

    /**
     * Returns the epoch-millis of local midnight (start of day) for the
     * day containing [nowMillis] in the given [zone].
     */
    fun localMidnightMillis(nowMillis: Long, zone: ZoneId): Long {
        val nowZoned = Instant.ofEpochMilli(nowMillis).atZone(zone)
        return nowZoned.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /**
     * Returns the epoch-millis of the next local midnight strictly after [nowMillis].
     */
    fun nextLocalMidnightMillis(nowMillis: Long, zone: ZoneId): Long {
        val nowZoned = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val tomorrow = nowZoned.toLocalDate().plusDays(1)
        return tomorrow.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /**
     * Returns the local epoch day for [nowMillis] in [zone].
     */
    fun localEpochDay(nowMillis: Long, zone: ZoneId): Long {
        return Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().toEpochDay()
    }
}
