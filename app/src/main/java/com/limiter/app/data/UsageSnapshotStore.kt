package com.limiter.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.limiter.app.model.UsageSnapshot
import kotlinx.coroutines.flow.Flow
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

    /** Publish a snapshot for a single package. */
    suspend fun publish(snapshot: UsageSnapshot.Available) {
        dataStore.edit { prefs ->
            val current = decodeSnapshots(prefs[UsageSnapshotKeys.SNAPSHOTS] ?: "").toMutableMap()
            current[snapshot.packageName] = snapshot
            prefs[UsageSnapshotKeys.SNAPSHOTS] = encodeSnapshots(current)
        }
    }

    /** Publish snapshots for multiple packages atomically. */
    suspend fun publishAll(snapshots: Map<String, UsageSnapshot.Available>) {
        dataStore.edit { prefs ->
            prefs[UsageSnapshotKeys.SNAPSHOTS] = encodeSnapshots(snapshots)
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

    internal fun decodeSnapshots(raw: String): Map<String, UsageSnapshot.Available> {
        if (raw.isBlank()) return emptyMap()
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            try {
                val parts = line.split("|")
                if (parts.size != 4) return@mapNotNull null
                val pkg = parts[0].trim()
                if (pkg.isBlank()) return@mapNotNull null
                val used = parts[1].toLong()
                val remaining = parts[2].toLong()
                val limit = parts[3].toInt()
                val snapshot = UsageSnapshot.Available(
                    packageName = pkg,
                    usedMillis = used,
                    remainingMillis = remaining,
                    limitMinutes = limit
                )
                pkg to snapshot
            } catch (_: Exception) {
                null
            }
        }.toMap()
    }
}
