package com.limiter.app.policy

/**
 * Determines the next poll delay for the usage monitor service.
 *
 * Rules (spec section 16):
 *   remaining > 30 minutes  → 5 minutes
 *   remaining <= 30 minutes → 2 minutes
 *   remaining <= 12 minutes → 30 seconds
 *
 * Then: actualDelay = min(plannedDelay, remainingMillis)
 *
 * Never schedule a poll later than the remaining time.
 * If remainingMillis <= 0, return 0 (immediate).
 */
object PollPlanner {

    private const val FIVE_MINUTES_MS = 5L * 60 * 1000
    private const val TWO_MINUTES_MS = 2L * 60 * 1000
    private const val THIRTY_SECONDS_MS = 30L * 1000

    private const val THIRTY_MINUTES_MS = 30L * 60 * 1000
    private const val TWELVE_MINUTES_MS = 12L * 60 * 1000

    /**
     * Calculate the next poll delay in milliseconds.
     *
     * @param remainingMillis remaining usage budget in milliseconds.
     *        If <= 0, the limit is already reached and the method returns 0.
     * @return delay in milliseconds before the next poll.
     */
    fun nextDelayMillis(remainingMillis: Long): Long {
        if (remainingMillis <= 0) return 0

        val planned = when {
            remainingMillis <= TWELVE_MINUTES_MS -> THIRTY_SECONDS_MS
            remainingMillis <= THIRTY_MINUTES_MS -> TWO_MINUTES_MS
            else -> FIVE_MINUTES_MS
        }

        return minOf(planned, remainingMillis)
    }
}
