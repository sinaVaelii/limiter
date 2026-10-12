package com.limiter.app.service

import com.limiter.app.model.LockRecord
import com.limiter.app.model.LockState

/**
 * Pure helper for monitoring loop watchdog per Stage R5 requirements.
 * Detects when any persisted lock deadline or retry backoff timestamp has expired,
 * allowing UsageMonitorService to trigger reconciliation without direct DPM coupling.
 */
object UsageMonitorWatchdog {

    /**
     * Returns true if any active lock deadline has passed, any retry backoff has elapsed,
     * or any ReleasePending state is ready to be resolved.
     */
    fun isAnyLockOrRetryDue(records: Map<String, LockRecord>, now: Long): Boolean {
        for ((_, record) in records) {
            val retryAt = record.nextUnlockRetryAtMillis
            if (retryAt != null && now >= retryAt) {
                return true
            }

            when (val state = record.state) {
                is LockState.LockedUntil -> {
                    if (now >= state.untilEpochMillis) {
                        return true
                    }
                }
                is LockState.ReleasePending -> {
                    if (retryAt == null || now >= retryAt) {
                        return true
                    }
                }
                is LockState.Unlocked -> {
                    // Nothing due
                }
            }
        }
        return false
    }

    /**
     * Executes the watchdog reconciliation step: checks if any lock or retry deadline is due.
     * If due, calls onReconcile and returns true. If not due, does nothing and returns false.
     * Never calls DPM or freezer directly.
     */
    suspend fun reconcileIfDue(
        records: Map<String, LockRecord>,
        now: Long,
        onReconcile: suspend () -> Unit
    ): Boolean {
        if (isAnyLockOrRetryDue(records, now)) {
            onReconcile()
            return true
        }
        return false
    }
}
