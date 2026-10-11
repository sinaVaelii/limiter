package com.limiter.app.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Tests for TimeMath per spec section 9.
 *
 * Covers: exact boundary, normal day, midnight, DST gap, DST overlap,
 * cross-midnight window.
 */
class TimeMathTest {

    private val utc = ZoneId.of("UTC")

    private fun zdtToMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: ZoneId): Long {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)
            .toInstant().toEpochMilli()
    }

    // ---- nextOccurrence ----

    @Test
    fun `nextOccurrence - normal day, target is later today`() {
        // Now is 10:00 UTC, target is 14:00 (840 minutes)
        val nowMillis = zdtToMillis(2024, 6, 15, 10, 0, utc)
        val result = TimeMath.nextOccurrence(nowMillis, utc, 840)
        val expected = zdtToMillis(2024, 6, 15, 14, 0, utc)
        assertEquals(expected, result)
    }

    @Test
    fun `nextOccurrence - normal day, target already passed today`() {
        // Now is 15:00 UTC, target is 14:00 (840 minutes) → should be tomorrow
        val nowMillis = zdtToMillis(2024, 6, 15, 15, 0, utc)
        val result = TimeMath.nextOccurrence(nowMillis, utc, 840)
        val expected = zdtToMillis(2024, 6, 16, 14, 0, utc)
        assertEquals(expected, result)
    }

    @Test
    fun `nextOccurrence - exact boundary, now is exactly the target time`() {
        // Now is exactly 14:00 UTC, target is 14:00 (840 minutes) → strictly after → tomorrow
        val nowMillis = zdtToMillis(2024, 6, 15, 14, 0, utc)
        val result = TimeMath.nextOccurrence(nowMillis, utc, 840)
        val expected = zdtToMillis(2024, 6, 16, 14, 0, utc)
        assertEquals(expected, result)
    }

    @Test
    fun `nextOccurrence - midnight target (minuteOfDay 0)`() {
        // Now is 23:30, target is midnight (0) → should be tomorrow's midnight
        val nowMillis = zdtToMillis(2024, 6, 15, 23, 30, utc)
        val result = TimeMath.nextOccurrence(nowMillis, utc, 0)
        val expected = zdtToMillis(2024, 6, 16, 0, 0, utc)
        assertEquals(expected, result)
    }

    @Test
    fun `nextOccurrence - midnight target, now is early morning`() {
        // Now is 00:01, target is midnight (0) → should be tomorrow's midnight
        val nowMillis = zdtToMillis(2024, 6, 15, 0, 1, utc)
        val result = TimeMath.nextOccurrence(nowMillis, utc, 0)
        val expected = zdtToMillis(2024, 6, 16, 0, 0, utc)
        assertEquals(expected, result)
    }

    @Test
    fun `nextOccurrence - DST gap, spring forward`() {
        // US/Eastern: 2024-03-10 at 2:00 AM clocks spring forward to 3:00 AM
        // Target: 2:30 AM (150 minutes) — falls in the gap
        // java.time adjusts to 3:30 AM
        val eastern = ZoneId.of("US/Eastern")
        val nowMillis = zdtToMillis(2024, 3, 10, 1, 0, eastern)
        val result = TimeMath.nextOccurrence(nowMillis, eastern, 150)

        // 2:30 AM doesn't exist, java.time adjusts to 3:30 AM EDT
        val resultZdt = Instant.ofEpochMilli(result).atZone(eastern)
        // The result should be on March 10 and after 1:00 AM
        assertEquals(10, resultZdt.dayOfMonth)
        assertTrue("Should be after now", result > nowMillis)
    }

    @Test
    fun `nextOccurrence - DST overlap, fall back`() {
        // US/Eastern: 2024-11-03 at 2:00 AM clocks fall back to 1:00 AM
        // Target: 1:30 AM (90 minutes) — occurs twice
        val eastern = ZoneId.of("US/Eastern")
        // Now is 0:30 AM EDT on Nov 3
        val nowMillis = zdtToMillis(2024, 11, 3, 0, 30, eastern)
        val result = TimeMath.nextOccurrence(nowMillis, eastern, 90)

        // Should be 1:30 AM on Nov 3 (the first occurrence, EDT)
        assertTrue("Should be after now", result > nowMillis)
        val resultZdt = Instant.ofEpochMilli(result).atZone(eastern)
        assertEquals(3, resultZdt.dayOfMonth)
        assertEquals(1, resultZdt.hour)
        assertEquals(30, resultZdt.minute)
    }

    @Test
    fun `nextOccurrence - cross-midnight window`() {
        // Hide at 22:00 (1320), reveal at 7:00 (420)
        // Now is 23:00 → nextOccurrence(reveal=420) should be tomorrow 7:00
        val nowMillis = zdtToMillis(2024, 6, 15, 23, 0, utc)
        val result = TimeMath.nextOccurrence(nowMillis, utc, 420)
        val expected = zdtToMillis(2024, 6, 16, 7, 0, utc)
        assertEquals(expected, result)
    }

    @Test
    fun `nextOccurrence - one minute before target`() {
        // Now is 13:59, target is 14:00 (840) → today
        val nowMillis = zdtToMillis(2024, 6, 15, 13, 59, utc)
        val result = TimeMath.nextOccurrence(nowMillis, utc, 840)
        val expected = zdtToMillis(2024, 6, 15, 14, 0, utc)
        assertEquals(expected, result)
    }

    // ---- localMidnightMillis ----

    @Test
    fun `localMidnightMillis - normal day`() {
        val nowMillis = zdtToMillis(2024, 6, 15, 14, 30, utc)
        val result = TimeMath.localMidnightMillis(nowMillis, utc)
        val expected = zdtToMillis(2024, 6, 15, 0, 0, utc)
        assertEquals(expected, result)
    }

    @Test
    fun `localMidnightMillis - different timezone`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        // 2024-06-15 14:30 JST
        val nowMillis = zdtToMillis(2024, 6, 15, 14, 30, tokyo)
        val result = TimeMath.localMidnightMillis(nowMillis, tokyo)
        val expected = zdtToMillis(2024, 6, 15, 0, 0, tokyo)
        assertEquals(expected, result)
    }

    // ---- nextLocalMidnightMillis ----

    @Test
    fun `nextLocalMidnightMillis - normal day`() {
        val nowMillis = zdtToMillis(2024, 6, 15, 14, 30, utc)
        val result = TimeMath.nextLocalMidnightMillis(nowMillis, utc)
        val expected = zdtToMillis(2024, 6, 16, 0, 0, utc)
        assertEquals(expected, result)
    }

    @Test
    fun `nextLocalMidnightMillis - exactly at midnight`() {
        val nowMillis = zdtToMillis(2024, 6, 15, 0, 0, utc)
        val result = TimeMath.nextLocalMidnightMillis(nowMillis, utc)
        val expected = zdtToMillis(2024, 6, 16, 0, 0, utc)
        assertEquals(expected, result)
    }

    // ---- localEpochDay ----

    @Test
    fun `localEpochDay - returns correct day`() {
        val nowMillis = zdtToMillis(2024, 6, 15, 14, 30, utc)
        val result = TimeMath.localEpochDay(nowMillis, utc)
        val expected = LocalDate.of(2024, 6, 15).toEpochDay()
        assertEquals(expected, result)
    }

    @Test
    fun `localEpochDay - different timezone shifts day boundary`() {
        val tokyo = ZoneId.of("Asia/Tokyo") // UTC+9
        // 2024-06-15 01:00 UTC = 2024-06-15 10:00 JST
        val utcMillis = zdtToMillis(2024, 6, 15, 1, 0, utc)
        val utcDay = TimeMath.localEpochDay(utcMillis, utc)
        val tokyoDay = TimeMath.localEpochDay(utcMillis, tokyo)
        // Both should see June 15 in this case
        assertEquals(utcDay, tokyoDay)

        // But 2024-06-14 20:00 UTC = 2024-06-15 05:00 JST
        val utcMillis2 = zdtToMillis(2024, 6, 14, 20, 0, utc)
        val utcDay2 = TimeMath.localEpochDay(utcMillis2, utc)
        val tokyoDay2 = TimeMath.localEpochDay(utcMillis2, tokyo)
        assertEquals(LocalDate.of(2024, 6, 14).toEpochDay(), utcDay2)
        assertEquals(LocalDate.of(2024, 6, 15).toEpochDay(), tokyoDay2)
    }
}
