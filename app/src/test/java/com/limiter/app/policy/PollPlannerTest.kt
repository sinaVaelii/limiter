package com.limiter.app.policy

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for PollPlanner per spec section 16.
 *
 * Rules:
 *   remaining > 30 min → 5 min
 *   remaining <= 30 min → 2 min
 *   remaining <= 12 min → 30 sec
 *   actualDelay = min(planned, remaining)
 *   remaining <= 0 → 0
 */
class PollPlannerTest {

    companion object {
        private const val MIN = 60_000L
        private const val SEC = 1_000L
    }

    @Test
    fun `remaining over 30 minutes returns 5 minutes`() {
        assertEquals(5 * MIN, PollPlanner.nextDelayMillis(31 * MIN))
    }

    @Test
    fun `remaining exactly 30 minutes returns 2 minutes`() {
        assertEquals(2 * MIN, PollPlanner.nextDelayMillis(30 * MIN))
    }

    @Test
    fun `remaining 20 minutes returns 2 minutes`() {
        assertEquals(2 * MIN, PollPlanner.nextDelayMillis(20 * MIN))
    }

    @Test
    fun `remaining exactly 12 minutes returns 30 seconds`() {
        assertEquals(30 * SEC, PollPlanner.nextDelayMillis(12 * MIN))
    }

    @Test
    fun `remaining 5 minutes returns 30 seconds`() {
        assertEquals(30 * SEC, PollPlanner.nextDelayMillis(5 * MIN))
    }

    @Test
    fun `remaining less than planned delay is clamped`() {
        // remaining = 10 seconds, planned = 30 seconds → return 10 seconds
        assertEquals(10 * SEC, PollPlanner.nextDelayMillis(10 * SEC))
    }

    @Test
    fun `remaining 1 ms returns 1 ms`() {
        assertEquals(1, PollPlanner.nextDelayMillis(1))
    }

    @Test
    fun `remaining zero returns zero`() {
        assertEquals(0, PollPlanner.nextDelayMillis(0))
    }

    @Test
    fun `remaining negative returns zero`() {
        assertEquals(0, PollPlanner.nextDelayMillis(-1000))
    }

    @Test
    fun `remaining very large returns 5 minutes`() {
        assertEquals(5 * MIN, PollPlanner.nextDelayMillis(Long.MAX_VALUE))
    }

    @Test
    fun `remaining at 30 min boundary - 30 min exactly uses 2 min tier`() {
        // 30 min = 1,800,000ms → <= 30 but > 12 → 2 min
        assertEquals(2 * MIN, PollPlanner.nextDelayMillis(30 * MIN))
    }

    @Test
    fun `remaining at 12 min boundary - 12 min exactly uses 30 sec tier`() {
        // 12 min = 720,000ms → <= 12 → 30 sec
        assertEquals(30 * SEC, PollPlanner.nextDelayMillis(12 * MIN))
    }

    @Test
    fun `remaining just above 30 min returns 5 minutes`() {
        assertEquals(5 * MIN, PollPlanner.nextDelayMillis(30 * MIN + 1))
    }

    @Test
    fun `remaining just above 12 min returns 2 minutes`() {
        assertEquals(2 * MIN, PollPlanner.nextDelayMillis(12 * MIN + 1))
    }

    @Test
    fun `clamp applies when remaining less than 30 seconds in low tier`() {
        // remaining = 15 seconds, planned = 30 seconds → return 15 seconds
        assertEquals(15 * SEC, PollPlanner.nextDelayMillis(15 * SEC))
    }
}
