package com.limiter.app.model

/**
 * Snapshot of a single target package's usage status.
 *
 * Authoritative fields are [usedMillis] and [remainingMillis].
 * [remainingMinutes] is derived for display/planning only.
 */
sealed interface UsageSnapshot {
    /** Usage access is not granted. */
    data object NoUsageAccess : UsageSnapshot

    /** The package has no active target configuration. */
    data object Idle : UsageSnapshot

    /** Usage data is available. */
    data class Available(
        val packageName: String,
        val usedMillis: Long,
        val remainingMillis: Long,
        val limitMinutes: Int
    ) : UsageSnapshot {
        /** Derived for display only — never use for enforcement decisions. */
        val remainingMinutes: Int
            get() = (remainingMillis / 60_000L).coerceAtLeast(0).toInt()
    }
}
