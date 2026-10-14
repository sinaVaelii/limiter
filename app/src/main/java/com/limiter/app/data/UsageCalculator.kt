package com.limiter.app.data

import com.limiter.app.model.UsageEventRecord
import com.limiter.app.model.UsageEventRecord.EventType

/**
 * Pure-Kotlin usage calculator.
 *
 * Computes foreground time from a list of usage events for a specific package
 * within a time window. No Android dependencies.
 *
 * Maintains a set of active activities. Foreground time accumulates while
 * at least one activity is active.
 *
 * Boundary rules:
 * - Events before fromMillis are processed to build initial state but
 *   time is only counted from fromMillis onward.
 * - An orphan PAUSED/STOPPED event (no matching active activity) is ignored.
 * - DEVICE_SHUTDOWN clears all active activity state.
 * - Still-active activities at toMillis contribute time up to toMillis.
 */
object UsageCalculator {

    /** Maximum duration capped for a single continuous foreground session between RESUME and PAUSE. */
    const val MAX_SESSION_DURATION_MILLIS: Long = 2L * 60 * 60 * 1000 // 2 hours

    /** Maximum duration capped for an unclosed session spanning to window end without closing events. */
    const val MAX_UNCLOSED_SESSION_MILLIS: Long = 2L * 60 * 60 * 1000 // 2 hours

    /** Target package for one-time test cutoff */
    const val CLASH_ROYALE_PACKAGE = "com.supercell.clashroyale"

    /**
     * One-time cutoff timestamp for com.supercell.clashroyale (Oct 14, 2026).
     * Events before this millisecond are ignored so pre-install clock-tamper test artifacts
     * are zeroed out for today, while all future usage from this exact moment onward is counted normally.
     */
    const val CLASH_ROYALE_TEST_CUTOFF_EPOCH_MILLIS: Long = 1792005933309L

    /**
     * Calculate foreground milliseconds for [packageName] within [fromMillis, toMillis].
     *
     * [events] should include a lookback period before [fromMillis] so the calculator
     * can determine which activities were already active at the window start.
     */
    fun foregroundMillis(
        events: List<UsageEventRecord>,
        packageName: String,
        fromMillis: Long,
        toMillis: Long
    ): Long {
        require(fromMillis <= toMillis) {
            "fromMillis ($fromMillis) must be <= toMillis ($toMillis)"
        }

        val effectiveFromMillis = if (packageName == CLASH_ROYALE_PACKAGE) {
            maxOf(fromMillis, CLASH_ROYALE_TEST_CUTOFF_EPOCH_MILLIS)
        } else {
            fromMillis
        }

        if (toMillis < effectiveFromMillis) {
            return 0L
        }

        // Filter to relevant package only, sorted by timestamp; ignore future-timestamped events and pre-cutoff Clash Royale test events
        val relevant = events
            .filter {
                val matchesPkgOrShutdown = (it.packageName == packageName || it.eventType == EventType.DEVICE_SHUTDOWN)
                val notFuture = it.timestampMillis <= toMillis
                val notBeforeClashRoyaleCutoff = if (it.packageName == CLASH_ROYALE_PACKAGE) {
                    it.timestampMillis >= CLASH_ROYALE_TEST_CUTOFF_EPOCH_MILLIS
                } else {
                    true
                }
                matchesPkgOrShutdown && notFuture && notBeforeClashRoyaleCutoff
            }
            .sortedBy { it.timestampMillis }

        // Track active activities by their className (or "unknown" if null)
        val activeActivities = mutableSetOf<String>()
        var totalForegroundMs = 0L
        var lastForegroundStart: Long? = null

        fun activityKey(event: UsageEventRecord): String {
            return event.className ?: "unknown"
        }

        fun startForegroundIfNeeded(atMillis: Long) {
            if (lastForegroundStart == null && activeActivities.isNotEmpty()) {
                // Clamp to window start
                lastForegroundStart = maxOf(atMillis, effectiveFromMillis)
            }
        }

        fun stopForegroundIfNeeded(atMillis: Long) {
            if (activeActivities.isEmpty() && lastForegroundStart != null) {
                val end = minOf(atMillis, toMillis)
                val start = lastForegroundStart!!
                if (end > start) {
                    val rawDuration = end - start
                    val duration = minOf(rawDuration, MAX_SESSION_DURATION_MILLIS)
                    totalForegroundMs += duration
                }
                lastForegroundStart = null
            }
        }

        for (event in relevant) {
            when (event.eventType) {
                EventType.ACTIVITY_RESUMED -> {
                    if (event.packageName != packageName) continue
                    val key = activityKey(event)
                    val wasEmpty = activeActivities.isEmpty()
                    activeActivities.add(key)
                    if (wasEmpty) {
                        startForegroundIfNeeded(event.timestampMillis)
                    }
                }

                EventType.ACTIVITY_PAUSED,
                EventType.ACTIVITY_STOPPED -> {
                    if (event.packageName != packageName) continue
                    val key = activityKey(event)
                    // Orphan check: only remove if present
                    if (key in activeActivities) {
                        activeActivities.remove(key)
                        stopForegroundIfNeeded(event.timestampMillis)
                    }
                    // Orphan events are ignored (no active state for that activity)
                }

                EventType.DEVICE_SHUTDOWN -> {
                    // Clears all active activities regardless of package
                    if (activeActivities.isNotEmpty()) {
                        activeActivities.clear()
                        stopForegroundIfNeeded(event.timestampMillis)
                    }
                }
            }
        }

        // If still active at end of window, count up to toMillis (capped to prevent clock-jump zombie session inflation)
        if (activeActivities.isNotEmpty() && lastForegroundStart != null) {
            val end = toMillis
            val start = lastForegroundStart!!
            if (end > start) {
                val rawDuration = end - start
                val duration = minOf(rawDuration, MAX_UNCLOSED_SESSION_MILLIS)
                totalForegroundMs += duration
            }
        }

        return totalForegroundMs
    }
}
