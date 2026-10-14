package com.limiter.app.data

import com.limiter.app.model.LockRecord
import com.limiter.app.model.LockState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory [LockStateStore] for unit testing.
 *
 * Implements the same retry backoff sequence as DataStoreLockStateStore.
 */
class InMemoryLockStateStore : LockStateStore {

    private val _records = MutableStateFlow<Map<String, LockRecord>>(emptyMap())

    override val states: Flow<Map<String, LockRecord>> = _records

    override suspend fun snapshot(): Map<String, LockRecord> = _records.value

    override suspend fun ensureRecord(pkg: String) {
        _records.update { current ->
            if (pkg in current) current
            else current + (pkg to LockRecord(LockState.Unlocked))
        }
    }

    override suspend fun lockUntilAtLeast(pkg: String, until: Long) {
        _records.update { current ->
            val existing = current[pkg]
            val existingUntil = (existing?.state as? LockState.LockedUntil)?.untilEpochMillis ?: 0L
            val effectiveUntil = maxOf(existingUntil, until)
            current + (pkg to LockRecord(
                state = LockState.LockedUntil(effectiveUntil),
                unlockFailureCount = existing?.unlockFailureCount ?: 0,
                nextUnlockRetryAtMillis = existing?.nextUnlockRetryAtMillis
            ))
        }
    }

    override suspend fun markUnlockedIfExpired(pkg: String, now: Long): Boolean {
        var transitioned = false
        _records.update { current ->
            val existing = current[pkg]
            val canUnlock = when (val state = existing?.state) {
                is LockState.LockedUntil -> now >= state.untilEpochMillis
                is LockState.ReleasePending -> true
                else -> false
            }
            if (canUnlock) {
                transitioned = true
                current + (pkg to LockRecord(LockState.Unlocked))
            } else {
                current
            }
        }
        return transitioned
    }

    override suspend fun recordUnlock(pkg: String) {
        _records.update { current ->
            current + (pkg to LockRecord(
                state = LockState.Unlocked,
                unlockFailureCount = 0,
                nextUnlockRetryAtMillis = null,
                visibilityRestored = false,
                unsuspensionRestored = false
            ))
        }
    }

    override suspend fun recordReleasePending(
        pkg: String,
        now: Long,
        visibilityRestored: Boolean,
        unsuspensionRestored: Boolean
    ) {
        _records.update { current ->
            val existing = current[pkg]
            current + (pkg to LockRecord(
                state = LockState.ReleasePending(
                    requestedAtMillis = now,
                    visibilityRestored = visibilityRestored,
                    unsuspensionRestored = unsuspensionRestored
                ),
                unlockFailureCount = existing?.unlockFailureCount ?: 0,
                nextUnlockRetryAtMillis = existing?.nextUnlockRetryAtMillis,
                visibilityRestored = visibilityRestored,
                unsuspensionRestored = unsuspensionRestored
            ))
        }
    }

    override suspend fun recordUnlockFailure(
        pkg: String,
        now: Long,
        visibilityRestored: Boolean,
        unsuspensionRestored: Boolean
    ) {
        _records.update { current ->
            val existing = current[pkg] ?: LockRecord(
                state = LockState.ReleasePending(
                    requestedAtMillis = now,
                    visibilityRestored = visibilityRestored,
                    unsuspensionRestored = unsuspensionRestored
                ),
                unlockFailureCount = 0
            )

            val newCount = existing.unlockFailureCount + 1
            val backoffIndex = (newCount - 1).coerceIn(
                0, DataStoreLockStateStore.RETRY_BACKOFF_MILLIS.size - 1
            )
            val backoffMs = DataStoreLockStateStore.RETRY_BACKOFF_MILLIS[backoffIndex]
            val nextRetry = now + backoffMs

            val newState = when (val state = existing.state) {
                is LockState.LockedUntil -> state // Retain LockedUntil until explicitly unlocked
                is LockState.ReleasePending -> state.copy(
                    visibilityRestored = visibilityRestored,
                    unsuspensionRestored = unsuspensionRestored
                )
                is LockState.Unlocked -> LockState.ReleasePending(
                    requestedAtMillis = now,
                    visibilityRestored = visibilityRestored,
                    unsuspensionRestored = unsuspensionRestored
                )
            }

            current + (pkg to existing.copy(
                state = newState,
                unlockFailureCount = newCount,
                nextUnlockRetryAtMillis = nextRetry,
                visibilityRestored = visibilityRestored,
                unsuspensionRestored = unsuspensionRestored
            ))
        }
    }

    override suspend fun clearUnlockRetry(pkg: String) {
        _records.update { current ->
            val existing = current[pkg] ?: return@update current
            current + (pkg to existing.copy(
                unlockFailureCount = 0,
                nextUnlockRetryAtMillis = null,
                visibilityRestored = false,
                unsuspensionRestored = false
            ))
        }
    }

    override suspend fun forget(pkg: String): Boolean {
        var removed = false
        _records.update { current ->
            val existing = current[pkg]
            if (existing != null && existing.state is LockState.Unlocked) {
                removed = true
                current - pkg
            } else {
                current
            }
        }
        return removed
    }
}
