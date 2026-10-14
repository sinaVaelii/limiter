package com.limiter.app.data

import com.limiter.app.model.UsageEventRecord
import com.limiter.app.model.UsageEventRecord.EventType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for UsageCalculator per spec section 13.
 *
 * Covers: multiple activities, overlapping activities, resume->pause,
 * resume->stop, resume before window + pause inside window,
 * activity still active at toMillis, orphan pause, orphan stop,
 * device shutdown, other packages, out-of-window events.
 */
class UsageCalculatorTest {

    private val pkg = "com.example.app"
    private val otherPkg = "com.example.other"

    private val from = 1000L
    private val to = 5000L

    private fun event(
        packageName: String = pkg,
        className: String? = "Activity",
        timestamp: Long,
        type: EventType
    ) = UsageEventRecord(packageName, className, timestamp, type)

    // ---- resume -> pause ----

    @Test
    fun `simple resume then pause within window`() {
        val events = listOf(
            event(timestamp = 2000, type = EventType.ACTIVITY_RESUMED),
            event(timestamp = 3000, type = EventType.ACTIVITY_PAUSED)
        )
        assertEquals(1000, UsageCalculator.foregroundMillis(events, pkg, from, to))
    }

    // ---- resume -> stop ----

    @Test
    fun `resume then stop within window`() {
        val events = listOf(
            event(timestamp = 2000, type = EventType.ACTIVITY_RESUMED),
            event(timestamp = 4000, type = EventType.ACTIVITY_STOPPED)
        )
        assertEquals(2000, UsageCalculator.foregroundMillis(events, pkg, from, to))
    }

    // ---- activity still active at toMillis ----

    @Test
    fun `activity still active at end of window`() {
        val events = listOf(
            event(timestamp = 2000, type = EventType.ACTIVITY_RESUMED)
        )
        // Active from 2000 to 5000 = 3000ms
        assertEquals(3000, UsageCalculator.foregroundMillis(events, pkg, from, to))
    }

    // ---- resume before window + pause inside window ----

    @Test
    fun `resume before window, pause inside window`() {
        val events = listOf(
            event(timestamp = 500, type = EventType.ACTIVITY_RESUMED),  // before window
            event(timestamp = 3000, type = EventType.ACTIVITY_PAUSED)   // inside window
        )
        // Active from 500, but clamped to window start (1000) to 3000 = 2000ms
        assertEquals(2000, UsageCalculator.foregroundMillis(events, pkg, from, to))
    }

    // ---- multiple activities ----

    @Test
    fun `multiple sequential activities`() {
        val events = listOf(
            event(className = "A", timestamp = 1500, type = EventType.ACTIVITY_RESUMED),
            event(className = "A", timestamp = 2000, type = EventType.ACTIVITY_PAUSED),
            event(className = "B", timestamp = 3000, type = EventType.ACTIVITY_RESUMED),
            event(className = "B", timestamp = 4000, type = EventType.ACTIVITY_PAUSED)
        )
        // A: 1500-2000 = 500ms, B: 3000-4000 = 1000ms, total = 1500ms
        assertEquals(1500, UsageCalculator.foregroundMillis(events, pkg, from, to))
    }

    // ---- overlapping activities ----

    @Test
    fun `overlapping activities counted once`() {
        val events = listOf(
            event(className = "A", timestamp = 1500, type = EventType.ACTIVITY_RESUMED),
            event(className = "B", timestamp = 2000, type = EventType.ACTIVITY_RESUMED),
            event(className = "A", timestamp = 3000, type = EventType.ACTIVITY_PAUSED),
            event(className = "B", timestamp = 4000, type = EventType.ACTIVITY_PAUSED)
        )
        // Foreground is continuous from 1500 to 4000 = 2500ms
        assertEquals(2500, UsageCalculator.foregroundMillis(events, pkg, from, to))
    }

    // ---- orphan pause ----

    @Test
    fun `orphan pause is ignored`() {
        val events = listOf(
            event(timestamp = 2000, type = EventType.ACTIVITY_PAUSED)
        )
        // No matching resume → orphan → 0ms
        assertEquals(0, UsageCalculator.foregroundMillis(events, pkg, from, to))
    }

    // ---- orphan stop ----

    @Test
    fun `orphan stop is ignored`() {
        val events = listOf(
            event(timestamp = 2000, type = EventType.ACTIVITY_STOPPED)
        )
        assertEquals(0, UsageCalculator.foregroundMillis(events, pkg, from, to))
    }

    // ---- device shutdown ----

    @Test
    fun `device shutdown clears all active activities`() {
        val events = listOf(
            event(className = "A", timestamp = 1500, type = EventType.ACTIVITY_RESUMED),
            event(className = "B", timestamp = 2000, type = EventType.ACTIVITY_RESUMED),
            // Shutdown at 3000
            UsageEventRecord("android", null, 3000, EventType.DEVICE_SHUTDOWN)
        )
        // A+B active from 1500 to 3000 = 1500ms
        assertEquals(1500, UsageCalculator.foregroundMillis(events, pkg, from, to))
    }

    @Test
    fun `activity after device shutdown starts fresh`() {
        val events = listOf(
            event(className = "A", timestamp = 1500, type = EventType.ACTIVITY_RESUMED),
            UsageEventRecord("android", null, 2000, EventType.DEVICE_SHUTDOWN),
            event(className = "A", timestamp = 3000, type = EventType.ACTIVITY_RESUMED),
            event(className = "A", timestamp = 4000, type = EventType.ACTIVITY_PAUSED)
        )
        // First: 1500-2000 = 500ms, Second: 3000-4000 = 1000ms, total = 1500ms
        assertEquals(1500, UsageCalculator.foregroundMillis(events, pkg, from, to))
    }

    // ---- other packages ----

    @Test
    fun `events from other packages are ignored`() {
        val events = listOf(
            event(packageName = otherPkg, timestamp = 1500, type = EventType.ACTIVITY_RESUMED),
            event(packageName = otherPkg, timestamp = 4000, type = EventType.ACTIVITY_PAUSED)
        )
        assertEquals(0, UsageCalculator.foregroundMillis(events, pkg, from, to))
    }

    // ---- out-of-window events ----

    @Test
    fun `events entirely before window are ignored for counting`() {
        val events = listOf(
            event(timestamp = 100, type = EventType.ACTIVITY_RESUMED),
            event(timestamp = 500, type = EventType.ACTIVITY_PAUSED)
        )
        // Both before window, resume and pause happened before window → 0
        assertEquals(0, UsageCalculator.foregroundMillis(events, pkg, from, to))
    }

    @Test
    fun `events entirely after window are ignored`() {
        val events = listOf(
            event(timestamp = 6000, type = EventType.ACTIVITY_RESUMED),
            event(timestamp = 7000, type = EventType.ACTIVITY_PAUSED)
        )
        assertEquals(0, UsageCalculator.foregroundMillis(events, pkg, from, to))
    }

    // ---- empty events ----

    @Test
    fun `no events returns zero`() {
        assertEquals(0, UsageCalculator.foregroundMillis(emptyList(), pkg, from, to))
    }

    // ---- complex scenario ----

    @Test
    fun `complex mixed scenario`() {
        val events = listOf(
            // Resume A before window
            event(className = "A", timestamp = 500, type = EventType.ACTIVITY_RESUMED),
            // Resume B in window
            event(className = "B", timestamp = 1500, type = EventType.ACTIVITY_RESUMED),
            // Pause A in window
            event(className = "A", timestamp = 2000, type = EventType.ACTIVITY_PAUSED),
            // B still active, pause B
            event(className = "B", timestamp = 3000, type = EventType.ACTIVITY_PAUSED),
            // Other package (ignored)
            event(packageName = otherPkg, className = "X", timestamp = 3500, type = EventType.ACTIVITY_RESUMED),
            // Orphan stop for A (ignored)
            event(className = "A", timestamp = 3700, type = EventType.ACTIVITY_STOPPED),
            // Resume C
            event(className = "C", timestamp = 4000, type = EventType.ACTIVITY_RESUMED)
            // C still active at toMillis
        )
        // A active from 500 (clamped to 1000) to 2000, but B keeps foreground going
        // Timeline: 1000-3000 (A resumed before, B overlaps) + 4000-5000 (C)
        // = 2000 + 1000 = 3000
        assertEquals(3000, UsageCalculator.foregroundMillis(events, pkg, from, to))
    }

    // ---- regression tests: ordering, duplicates, invalid parameters ----

    @Test
    fun `events arriving out of order produce identical results to sorted events`() {
        val sortedEvents = listOf(
            event(className = "A", timestamp = 1500, type = EventType.ACTIVITY_RESUMED),
            event(className = "A", timestamp = 2000, type = EventType.ACTIVITY_PAUSED),
            event(className = "B", timestamp = 2500, type = EventType.ACTIVITY_RESUMED),
            event(className = "B", timestamp = 3500, type = EventType.ACTIVITY_PAUSED)
        )
        val reversedEvents = sortedEvents.reversed()
        val shuffledEvents = listOf(
            sortedEvents[2],
            sortedEvents[0],
            sortedEvents[3],
            sortedEvents[1]
        )

        val expected = UsageCalculator.foregroundMillis(sortedEvents, pkg, from, to)
        assertEquals(expected, UsageCalculator.foregroundMillis(reversedEvents, pkg, from, to))
        assertEquals(expected, UsageCalculator.foregroundMillis(shuffledEvents, pkg, from, to))
        assertEquals(1500, expected)
    }

    @Test
    fun `duplicate ACTIVITY_RESUMED events do not double-count time`() {
        val eventsWithDuplicates = listOf(
            event(className = "A", timestamp = 1500, type = EventType.ACTIVITY_RESUMED),
            event(className = "A", timestamp = 1600, type = EventType.ACTIVITY_RESUMED), // duplicate resume
            event(className = "A", timestamp = 1700, type = EventType.ACTIVITY_RESUMED), // duplicate resume
            event(className = "A", timestamp = 2500, type = EventType.ACTIVITY_PAUSED)
        )
        // Active from 1500 to 2500 = 1000ms
        assertEquals(1000, UsageCalculator.foregroundMillis(eventsWithDuplicates, pkg, from, to))
    }

    @Test
    fun `duplicate ACTIVITY_PAUSED events do not cause errors or negative time`() {
        val eventsWithDuplicates = listOf(
            event(className = "A", timestamp = 1500, type = EventType.ACTIVITY_RESUMED),
            event(className = "A", timestamp = 2000, type = EventType.ACTIVITY_PAUSED),
            event(className = "A", timestamp = 2100, type = EventType.ACTIVITY_PAUSED), // duplicate pause
            event(className = "A", timestamp = 2200, type = EventType.ACTIVITY_PAUSED)  // duplicate pause
        )
        // Active from 1500 to 2000 = 500ms
        assertEquals(500, UsageCalculator.foregroundMillis(eventsWithDuplicates, pkg, from, to))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid time window with fromMillis greater than toMillis throws exception`() {
        UsageCalculator.foregroundMillis(emptyList(), pkg, fromMillis = 5000, toMillis = 1000)
    }

    @Test
    fun `events with future timestamp beyond toMillis are ignored`() {
        val events = listOf(
            event(timestamp = 2000, type = EventType.ACTIVITY_RESUMED),
            event(timestamp = 3000, type = EventType.ACTIVITY_PAUSED),
            // Future events simulated after clock tamper
            event(timestamp = 100_000, type = EventType.ACTIVITY_RESUMED),
            event(timestamp = 200_000, type = EventType.ACTIVITY_PAUSED)
        )
        // to = 5000, future events at 100_000+ must be completely ignored
        assertEquals(1000, UsageCalculator.foregroundMillis(events, pkg, from, to))
    }

    @Test
    fun `unclosed session spanning huge time window is capped to MAX_UNCLOSED_SESSION_MILLIS`() {
        val start = 1_000_000L
        val tenHoursLater = start + 10L * 60 * 60 * 1000
        val events = listOf(
            event(timestamp = start, type = EventType.ACTIVITY_RESUMED)
        )
        val calculated = UsageCalculator.foregroundMillis(events, pkg, fromMillis = start, toMillis = tenHoursLater)
        assertEquals(UsageCalculator.MAX_UNCLOSED_SESSION_MILLIS, calculated)
    }

    @Test
    fun `paired session spanning huge time window is capped to MAX_SESSION_DURATION_MILLIS`() {
        val start = 1_000_000L
        val tenHoursLater = start + 10L * 60 * 60 * 1000
        val events = listOf(
            event(timestamp = start, type = EventType.ACTIVITY_RESUMED),
            event(timestamp = tenHoursLater, type = EventType.ACTIVITY_PAUSED)
        )
        val calculated = UsageCalculator.foregroundMillis(events, pkg, fromMillis = start, toMillis = tenHoursLater)
        assertEquals(UsageCalculator.MAX_SESSION_DURATION_MILLIS, calculated)
    }

    @Test
    fun `clash royale events before cutoff are ignored while events after cutoff count normally`() {
        val clashPkg = UsageCalculator.CLASH_ROYALE_PACKAGE
        val cutoff = UsageCalculator.CLASH_ROYALE_TEST_CUTOFF_EPOCH_MILLIS
        val dayStart = cutoff - 5L * 60 * 60 * 1000 // 5 hours before cutoff

        val preCutoffEvents = listOf(
            UsageEventRecord(clashPkg, "GameApp", cutoff - 4L * 60 * 60 * 1000, EventType.ACTIVITY_RESUMED),
            UsageEventRecord(clashPkg, "GameApp", cutoff - 2L * 60 * 60 * 1000, EventType.ACTIVITY_PAUSED)
        )
        // Pre-cutoff usage alone must yield 0ms
        val preCutoffUsage = UsageCalculator.foregroundMillis(
            preCutoffEvents,
            clashPkg,
            fromMillis = dayStart,
            toMillis = cutoff + 1000
        )
        assertEquals(0L, preCutoffUsage)

        // Events after cutoff must count normally
        val postCutoffEvents = preCutoffEvents + listOf(
            UsageEventRecord(clashPkg, "GameApp", cutoff + 10_000, EventType.ACTIVITY_RESUMED),
            UsageEventRecord(clashPkg, "GameApp", cutoff + 70_000, EventType.ACTIVITY_PAUSED)
        )
        val postCutoffUsage = UsageCalculator.foregroundMillis(
            postCutoffEvents,
            clashPkg,
            fromMillis = dayStart,
            toMillis = cutoff + 100_000
        )
        assertEquals(60_000L, postCutoffUsage)
    }
}
