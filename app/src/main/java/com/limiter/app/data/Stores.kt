package com.limiter.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

/**
 * DataStore delegates.
 *
 * Exactly one DataStore instance per file.
 * All preference delegates for a file live here.
 * No duplicate DataStore delegates.
 */

/** Settings store — persists target app configurations and pending changes. */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "limiter_settings"
)

/** Usage snapshot store — publishes current usage snapshots. */
val Context.usageSnapshotDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "limiter_usage_snapshots"
)

// Preference keys for settings
object SettingsKeys {
    /** JSON-encoded Map<String, TargetAppConfig> */
    val TARGETS = stringPreferencesKey("targets")

    /** JSON-encoded List<PendingChange> */
    val PENDING_CHANGES = stringPreferencesKey("pending_changes")
}

// Preference keys for usage snapshots
object UsageSnapshotKeys {
    /** JSON-encoded Map<String, UsageSnapshot.Available> */
    val SNAPSHOTS = stringPreferencesKey("usage_snapshots")
}

/** Lock state store — persists lock records with retry metadata. */
val Context.lockStateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "limiter_lock_state"
)

// Preference keys for lock state
object LockStateKeys {
    /** Encoded Map<String, LockRecord> */
    val LOCK_RECORDS = stringPreferencesKey("lock_records")
}
