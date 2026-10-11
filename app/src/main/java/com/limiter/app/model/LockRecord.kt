package com.limiter.app.model

/**
 * Persisted lock record per AGENTS.md §26.
 *
 * Contains enough information to handle retry without an alarm loop.
 *
 * For Unlocked: retry fields must be cleared.
 * For LockedUntil: retry fields may exist.
 */
data class LockRecord(
    val state: LockState,
    val unlockFailureCount: Int = 0,
    val nextUnlockRetryAtMillis: Long? = null,
    val visibilityRestored: Boolean = false,
    val unsuspensionRestored: Boolean = false
) {
    init {
        if (state is LockState.Unlocked) {
            require(unlockFailureCount == 0) {
                "Unlocked state must have zero failure count"
            }
            require(nextUnlockRetryAtMillis == null) {
                "Unlocked state must not have retry time"
            }
        }
    }
}
