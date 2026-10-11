package com.limiter.app.model

/**
 * Logical lock state for a target package.
 *
 * A persisted lock may be extended but never shortened.
 */
sealed interface LockState {
    data object Unlocked : LockState

    data class LockedUntil(
        val untilEpochMillis: Long
    ) : LockState

    data class ReleasePending(
        val requestedAtMillis: Long,
        val visibilityRestored: Boolean = false,
        val unsuspensionRestored: Boolean = false
    ) : LockState
}
