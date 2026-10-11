package com.limiter.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.limiter.app.model.PendingChange
import com.limiter.app.model.TargetAppConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Repository for target app configurations and pending changes.
 *
 * Backed by a single DataStore file.
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>
) {

    /** Flow of all current target configurations keyed by package name. */
    val targets: Flow<Map<String, TargetAppConfig>> = dataStore.data.map { prefs ->
        val raw = prefs[SettingsKeys.TARGETS] ?: ""
        Codec.decodeTargets(raw)
    }

    /** Flow of all pending changes. */
    val pendingChanges: Flow<List<PendingChange>> = dataStore.data.map { prefs ->
        val raw = prefs[SettingsKeys.PENDING_CHANGES] ?: ""
        Codec.decodePending(raw)
    }

    /** Snapshot of current targets. */
    suspend fun snapshotTargets(): Map<String, TargetAppConfig> {
        return targets.first()
    }

    /** Snapshot of current pending changes. */
    suspend fun snapshotPending(): List<PendingChange> {
        return pendingChanges.first()
    }

    /** Replace all targets atomically. */
    suspend fun setTargets(targets: Map<String, TargetAppConfig>) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.TARGETS] = Codec.encodeTargets(targets)
        }
    }

    /** Add or replace a single target. */
    suspend fun putTarget(config: TargetAppConfig) {
        dataStore.edit { prefs ->
            val current = Codec.decodeTargets(prefs[SettingsKeys.TARGETS] ?: "").toMutableMap()
            current[config.packageName] = config
            prefs[SettingsKeys.TARGETS] = Codec.encodeTargets(current)
        }
    }

    /** Remove a target by package name. */
    suspend fun removeTarget(packageName: String) {
        dataStore.edit { prefs ->
            val current = Codec.decodeTargets(prefs[SettingsKeys.TARGETS] ?: "").toMutableMap()
            current.remove(packageName)
            prefs[SettingsKeys.TARGETS] = Codec.encodeTargets(current)
        }
    }

    /** Replace all pending changes atomically. */
    suspend fun setPending(changes: List<PendingChange>) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.PENDING_CHANGES] = Codec.encodePending(changes)
        }
    }

    /** Add or replace a pending change for a specific package. */
    suspend fun putPending(change: PendingChange) {
        dataStore.edit { prefs ->
            val current = Codec.decodePending(prefs[SettingsKeys.PENDING_CHANGES] ?: "").toMutableList()
            current.removeAll { it.packageName == change.packageName }
            current.add(change)
            prefs[SettingsKeys.PENDING_CHANGES] = Codec.encodePending(current)
        }
    }

    /** Remove pending change for a specific package. */
    suspend fun removePending(packageName: String) {
        dataStore.edit { prefs ->
            val current = Codec.decodePending(prefs[SettingsKeys.PENDING_CHANGES] ?: "").toMutableList()
            current.removeAll { it.packageName == packageName }
            prefs[SettingsKeys.PENDING_CHANGES] = Codec.encodePending(current)
        }
    }
}
