package com.limiter.app.data

import com.limiter.app.model.LockRecord
import com.limiter.app.model.LockState
import kotlinx.coroutines.flow.Flow

/**
 * Persistence layer for lock records.
 *
 * Rules:
 *   lockUntilAtLeast: never shortens
 *   markUnlockedIfExpired: only succeeds when now >= until
 *   forget: only when package is logically unlocked
 */
interface LockStateStore {
    /** Flow of all lock records. */
    val states: Flow<Map<String, LockRecord>>

    /** Snapshot of current lock records. */
    suspend fun snapshot(): Map<String, LockRecord>

    /** Ensure a record exists for package, creating Unlocked if absent. */
    suspend fun ensureRecord(pkg: String)

    /**
     * Extend lock. Never shortens: max(existing, newUntil).
     */
    suspend fun lockUntilAtLeast(pkg: String, until: Long)

    /**
     * Transition to Unlocked only if now >= until.
     * Returns true if transition occurred.
     */
    suspend fun markUnlockedIfExpired(pkg: String, now: Long): Boolean

    /**
     * Record an unlock failure with backoff and optional partial restoration progress.
     */
    suspend fun recordUnlockFailure(
        pkg: String,
        now: Long,
        visibilityRestored: Boolean = false,
        unsuspensionRestored: Boolean = false
    )

    /**
     * Record a transition to ReleasePending with partial restoration progress.
     */
    suspend fun recordReleasePending(
        pkg: String,
        now: Long,
        visibilityRestored: Boolean = false,
        unsuspensionRestored: Boolean = false
    )

    /**
     * Unconditionally record Unlocked state and clear retry metadata.
     */
    suspend fun recordUnlock(pkg: String)

    /**
     * Clear retry metadata after successful unfreeze.
     */
    suspend fun clearUnlockRetry(pkg: String)

    /**
     * Remove record entirely. Only when package is logically unlocked.
     * Returns true if record was removed.
     */
    suspend fun forget(pkg: String): Boolean
}
