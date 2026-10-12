package com.limiter.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.limiter.app.model.LockRecord
import com.limiter.app.model.LockState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed [LockStateStore] per AGENTS.md §27.
 *
 * Retry backoff sequence (§ Retry):
 *   30s, 1m, 2m, 5m, 10m, 15m, 30m max
 */
class DataStoreLockStateStore(
    private val dataStore: DataStore<Preferences>
) : LockStateStore {

    companion object {
        /**
         * Retry backoff intervals in millis per AGENTS.md.
         */
        val RETRY_BACKOFF_MILLIS = longArrayOf(
            30_000L,         // 30 seconds
            60_000L,         // 1 minute
            120_000L,        // 2 minutes
            300_000L,        // 5 minutes
            600_000L,        // 10 minutes
            900_000L,        // 15 minutes
            1_800_000L       // 30 minutes max
        )
    }

    override val states: Flow<Map<String, LockRecord>> = dataStore.data.map { prefs ->
        val raw = prefs[LockStateKeys.LOCK_RECORDS] ?: ""
        LockRecordCodec.decode(raw)
    }

    override suspend fun snapshot(): Map<String, LockRecord> {
        return states.first()
    }

    override suspend fun ensureRecord(pkg: String) {
        dataStore.edit { prefs ->
            val current = LockRecordCodec.decode(prefs[LockStateKeys.LOCK_RECORDS] ?: "")
                .toMutableMap()
            if (pkg !in current) {
                current[pkg] = LockRecord(LockState.Unlocked)
                prefs[LockStateKeys.LOCK_RECORDS] = LockRecordCodec.encode(current)
            }
        }
    }

    override suspend fun lockUntilAtLeast(pkg: String, until: Long) {
        dataStore.edit { prefs ->
            val current = LockRecordCodec.decode(prefs[LockStateKeys.LOCK_RECORDS] ?: "")
                .toMutableMap()
            val existing = current[pkg]
            val existingUntil = (existing?.state as? LockState.LockedUntil)?.untilEpochMillis ?: 0L
            val effectiveUntil = maxOf(existingUntil, until)
            current[pkg] = LockRecord(
                state = LockState.LockedUntil(effectiveUntil),
                unlockFailureCount = existing?.unlockFailureCount ?: 0,
                nextUnlockRetryAtMillis = existing?.nextUnlockRetryAtMillis
            )
            prefs[LockStateKeys.LOCK_RECORDS] = LockRecordCodec.encode(current)
        }
    }

    override suspend fun markUnlockedIfExpired(pkg: String, now: Long): Boolean {
        var transitioned = false
        dataStore.edit { prefs ->
            val current = LockRecordCodec.decode(prefs[LockStateKeys.LOCK_RECORDS] ?: "")
                .toMutableMap()
            val existing = current[pkg]
            val canUnlock = when (val state = existing?.state) {
                is LockState.LockedUntil -> now >= state.untilEpochMillis
                is LockState.ReleasePending -> true
                else -> false
            }
            if (canUnlock) {
                current[pkg] = LockRecord(LockState.Unlocked)
                prefs[LockStateKeys.LOCK_RECORDS] = LockRecordCodec.encode(current)
                transitioned = true
            }
        }
        return transitioned
    }

    override suspend fun recordUnlock(pkg: String) {
        dataStore.edit { prefs ->
            val current = LockRecordCodec.decode(prefs[LockStateKeys.LOCK_RECORDS] ?: "")
                .toMutableMap()
            current[pkg] = LockRecord(
                state = LockState.Unlocked,
                unlockFailureCount = 0,
                nextUnlockRetryAtMillis = null,
                visibilityRestored = false,
                unsuspensionRestored = false
            )
            prefs[LockStateKeys.LOCK_RECORDS] = LockRecordCodec.encode(current)
        }
    }

    override suspend fun recordReleasePending(
        pkg: String,
        now: Long,
        visibilityRestored: Boolean,
        unsuspensionRestored: Boolean
    ) {
        dataStore.edit { prefs ->
            val current = LockRecordCodec.decode(prefs[LockStateKeys.LOCK_RECORDS] ?: "")
                .toMutableMap()
            val existing = current[pkg]
            current[pkg] = LockRecord(
                state = LockState.ReleasePending(
                    requestedAtMillis = now,
                    visibilityRestored = visibilityRestored,
                    unsuspensionRestored = unsuspensionRestored
                ),
                unlockFailureCount = existing?.unlockFailureCount ?: 0,
                nextUnlockRetryAtMillis = existing?.nextUnlockRetryAtMillis,
                visibilityRestored = visibilityRestored,
                unsuspensionRestored = unsuspensionRestored
            )
            prefs[LockStateKeys.LOCK_RECORDS] = LockRecordCodec.encode(current)
        }
    }

    override suspend fun recordUnlockFailure(
        pkg: String,
        now: Long,
        visibilityRestored: Boolean,
        unsuspensionRestored: Boolean
    ) {
        dataStore.edit { prefs ->
            val current = LockRecordCodec.decode(prefs[LockStateKeys.LOCK_RECORDS] ?: "")
                .toMutableMap()
            val existing = current[pkg] ?: LockRecord(
                state = LockState.ReleasePending(
                    requestedAtMillis = now,
                    visibilityRestored = visibilityRestored,
                    unsuspensionRestored = unsuspensionRestored
                ),
                unlockFailureCount = 0
            )

            val newCount = existing.unlockFailureCount + 1
            val backoffIndex = (newCount - 1).coerceIn(0, RETRY_BACKOFF_MILLIS.size - 1)
            val backoffMs = RETRY_BACKOFF_MILLIS[backoffIndex]
            val nextRetry = now + backoffMs

            val newState = when (val state = existing.state) {
                is LockState.LockedUntil -> state // Retain LockedUntil per §30
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

            current[pkg] = existing.copy(
                state = newState,
                unlockFailureCount = newCount,
                nextUnlockRetryAtMillis = nextRetry,
                visibilityRestored = visibilityRestored,
                unsuspensionRestored = unsuspensionRestored
            )
            prefs[LockStateKeys.LOCK_RECORDS] = LockRecordCodec.encode(current)
        }
    }

    override suspend fun clearUnlockRetry(pkg: String) {
        dataStore.edit { prefs ->
            val current = LockRecordCodec.decode(prefs[LockStateKeys.LOCK_RECORDS] ?: "")
                .toMutableMap()
            val existing = current[pkg] ?: return@edit
            current[pkg] = existing.copy(
                unlockFailureCount = 0,
                nextUnlockRetryAtMillis = null,
                visibilityRestored = false,
                unsuspensionRestored = false
            )
            prefs[LockStateKeys.LOCK_RECORDS] = LockRecordCodec.encode(current)
        }
    }

    override suspend fun forget(pkg: String): Boolean {
        var removed = false
        dataStore.edit { prefs ->
            val current = LockRecordCodec.decode(prefs[LockStateKeys.LOCK_RECORDS] ?: "")
                .toMutableMap()
            val existing = current[pkg]
            if (existing != null && existing.state is LockState.Unlocked) {
                current.remove(pkg)
                prefs[LockStateKeys.LOCK_RECORDS] = LockRecordCodec.encode(current)
                removed = true
            }
        }
        return removed
    }
}

/**
 * Codec for LockRecord serialization.
 * Format: pkg|state_type|untilOrReqEpochMillis|failureCount|nextRetryMillis|partials
 * state_type: U=Unlocked, L=LockedUntil, R=ReleasePending
 */
internal object LockRecordCodec {

    fun encode(records: Map<String, LockRecord>): String {
        return records.entries.joinToString(separator = "\n") { (pkg, record) ->
            when (val state = record.state) {
                is LockState.Unlocked -> "$pkg|U|0|0|"
                is LockState.LockedUntil -> {
                    val until = state.untilEpochMillis
                    val retryAt = record.nextUnlockRetryAtMillis?.toString() ?: ""
                    val vis = if (record.visibilityRestored) "1" else "0"
                    val unsusp = if (record.unsuspensionRestored) "1" else "0"
                    "$pkg|L|$until|${record.unlockFailureCount}|$retryAt|$vis,$unsusp"
                }
                is LockState.ReleasePending -> {
                    val req = state.requestedAtMillis
                    val retryAt = record.nextUnlockRetryAtMillis?.toString() ?: ""
                    val vis = if (state.visibilityRestored || record.visibilityRestored) "1" else "0"
                    val unsusp = if (state.unsuspensionRestored || record.unsuspensionRestored) "1" else "0"
                    "$pkg|R|$req|${record.unlockFailureCount}|$retryAt|$vis,$unsusp"
                }
            }
        }
    }

    fun decode(raw: String): Map<String, LockRecord> {
        if (raw.isBlank()) return emptyMap()
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            try {
                val parts = line.split("|")
                if (parts.size < 5) return@mapNotNull null
                val pkg = parts[0]
                val stateType = parts[1]
                val record = when (stateType) {
                    "U" -> LockRecord(LockState.Unlocked)
                    "L" -> {
                        val until = parts[2].toLong()
                        val failCount = parts[3].toInt()
                        val retryAt = parts[4].toLongOrNull()
                        val partials = if (parts.size >= 6) parts[5].split(",") else emptyList()
                        val vis = partials.getOrNull(0) == "1"
                        val unsusp = partials.getOrNull(1) == "1"
                        LockRecord(
                            state = LockState.LockedUntil(until),
                            unlockFailureCount = failCount,
                            nextUnlockRetryAtMillis = retryAt,
                            visibilityRestored = vis,
                            unsuspensionRestored = unsusp
                        )
                    }
                    "R" -> {
                        val req = parts[2].toLong()
                        val failCount = parts[3].toInt()
                        val retryAt = parts[4].toLongOrNull()
                        val partials = if (parts.size >= 6) parts[5].split(",") else emptyList()
                        val vis = partials.getOrNull(0) == "1"
                        val unsusp = partials.getOrNull(1) == "1"
                        LockRecord(
                            state = LockState.ReleasePending(
                                requestedAtMillis = req,
                                visibilityRestored = vis,
                                unsuspensionRestored = unsusp
                            ),
                            unlockFailureCount = failCount,
                            nextUnlockRetryAtMillis = retryAt,
                            visibilityRestored = vis,
                            unsuspensionRestored = unsusp
                        )
                    }
                    else -> return@mapNotNull null
                }
                pkg to record
            } catch (e: Exception) {
                null
            }
        }.toMap()
    }
}
