package com.limiter.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.limiter.app.model.UsageSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Publishes usage snapshots via DataStore.
 *
 * States: NoUsageAccess, Idle, Available.
 *
 * The monitor service publishes snapshots here;
 * the UI layer observes them.
 */
class UsageSnapshotStore(
    private val dataStore: DataStore<Preferences>
) {

    /** Flow of current usage snapshots keyed by package name. */
    val snapshots: Flow<Map<String, UsageSnapshot.Available>> = dataStore.data.map { prefs ->
        val raw = prefs[UsageSnapshotKeys.SNAPSHOTS] ?: ""
        decodeSnapshots(raw)
    }

    /** Retrieve the current stored day start millis (local midnight epoch millis). */
    suspend fun getStoredDayStartMillis(): Long {
        return dataStore.data.map { prefs ->
            prefs[UsageSnapshotKeys.DAY_START_MILLIS] ?: 0L
        }.first()
    }

    /** Retrieve current snapshot map synchronously from Flow. */
    suspend fun getSnapshots(): Map<String, UsageSnapshot.Available> {
        return snapshots.first()
    }

    /** Publish a snapshot for a single package. */
    suspend fun publish(snapshot: UsageSnapshot.Available, dayStartMillis: Long = 0L) {
        publishAll(mapOf(snapshot.packageName to snapshot), dayStartMillis)
    }

    /**
     * Publish snapshots for multiple packages atomically.
     * Enforces Monotonic Daily Usage (Anti-Reset):
     * Within the same local calendar day (dayStartMillis), usage can NEVER decrease or reset.
     */
    suspend fun publishAll(
        snapshots: Map<String, UsageSnapshot.Available>,
        dayStartMillis: Long = 0L
    ) {
        dataStore.edit { prefs ->
            val storedDayStart = prefs[UsageSnapshotKeys.DAY_START_MILLIS] ?: 0L
            val existingSnapshots = decodeSnapshots(prefs[UsageSnapshotKeys.SNAPSHOTS] ?: "")

            val merged = if (dayStartMillis > 0L && dayStartMillis == storedDayStart) {
                // Same local calendar day: enforce monotonic usage (usage can NEVER decrease)
                val result = existingSnapshots.toMutableMap()
                for ((pkg, incoming) in snapshots) {
                    val existing = existingSnapshots[pkg]
                    if (existing != null) {
                        val monotonicUsed = maxOf(existing.usedMillis, incoming.usedMillis)
                        val limitMillis = incoming.limitMinutes * 60_000L
                        val monotonicRemaining = (limitMillis - monotonicUsed).coerceAtLeast(0L)
                        result[pkg] = incoming.copy(
                            usedMillis = monotonicUsed,
                            remainingMillis = monotonicRemaining
                        )
                    } else {
                        result[pkg] = incoming
                    }
                }
                result
            } else if (dayStartMillis > 0L && dayStartMillis < storedDayStart) {
                // Clock jumped backwards: do not allow reset, enforce monotonic usage
                val result = existingSnapshots.toMutableMap()
                for ((pkg, incoming) in snapshots) {
                    val existing = existingSnapshots[pkg]
                    if (existing != null) {
                        val monotonicUsed = maxOf(existing.usedMillis, incoming.usedMillis)
                        val limitMillis = incoming.limitMinutes * 60_000L
                        val monotonicRemaining = (limitMillis - monotonicUsed).coerceAtLeast(0L)
                        result[pkg] = incoming.copy(
                            usedMillis = monotonicUsed,
                            remainingMillis = monotonicRemaining
                        )
                    } else {
                        result[pkg] = incoming
                    }
                }
                result
            } else {
                // New day (dayStartMillis > storedDayStart) or unversioned caller (dayStartMillis == 0L)
                if (dayStartMillis > 0L) {
                    prefs[UsageSnapshotKeys.DAY_START_MILLIS] = dayStartMillis
                }
                snapshots
            }

            prefs[UsageSnapshotKeys.SNAPSHOTS] = encodeSnapshots(merged)
        }
    }

    /** Clear a single package from snapshots. */
    suspend fun clearPackage(packageName: String) {
        dataStore.edit { prefs ->
            val current = decodeSnapshots(prefs[UsageSnapshotKeys.SNAPSHOTS] ?: "").toMutableMap()
            if (current.remove(packageName) != null) {
                prefs[UsageSnapshotKeys.SNAPSHOTS] = encodeSnapshots(current)
            }
        }
    }

    /** Clear all snapshots. */
    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(UsageSnapshotKeys.SNAPSHOTS)
        }
    }

    private fun encodeSnapshots(snapshots: Map<String, UsageSnapshot.Available>): String {
        return snapshots.entries.joinToString(separator = "\n") { (_, s) ->
            "${s.packageName}|${s.usedMillis}|${s.remainingMillis}|${s.limitMinutes}"
        }
    }

    companion object {
        private fun safeLogW(msg: String, tr: Throwable? = null) {
            try {
                if (tr != null) android.util.Log.w("Limiter", msg, tr) else android.util.Log.w("Limiter", msg)
            } catch (_: Throwable) {
                System.err.println("Limiter: $msg")
            }
        }
    }

    internal fun decodeSnapshots(raw: String): Map<String, UsageSnapshot.Available> {
        if (raw.isBlank()) return emptyMap()
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            try {
                val parts = line.split("|")
                if (parts.size != 4) {
                    safeLogW("Skipping corrupt usage snapshot line (expected 4 fields): $line")
                    return@mapNotNull null
                }
                val pkg = parts[0].trim()
                if (pkg.isBlank()) {
                    safeLogW("Skipping corrupt usage snapshot line (empty package): $line")
                    return@mapNotNull null
                }
                val used = parts[1].toLong()
                val remaining = parts[2].toLong()
                val limit = parts[3].toInt()
                if (used < 0 || remaining < 0 || limit <= 0) {
                    safeLogW("Skipping corrupt usage snapshot line (invalid used/remaining/limit): $line")
                    return@mapNotNull null
                }
                val snapshot = UsageSnapshot.Available(
                    packageName = pkg,
                    usedMillis = used,
                    remainingMillis = remaining,
                    limitMinutes = limit
                )
                pkg to snapshot
            } catch (e: Exception) {
                safeLogW("Skipping corrupt usage snapshot line: $line ($e)")
                null
            }
        }.toMap()
    }
}
