package com.limiter.app.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.limiter.app.model.UsageEventRecord
import com.limiter.app.model.UsageEventRecord.EventType
import com.limiter.app.time.TimeMath
import com.limiter.app.time.TimeSource

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads usage events from the Android UsageStatsManager and maps
 * them to the internal [UsageEventRecord] model.
 *
 * Lookback: Queries from 3 hours before local midnight to catch activities
 * resumed late in the evening that remained active across the midnight boundary.
 *
 * M4 Optimization: Narrowed from 48h to 3h because activities spanning midnight
 * begin in the late evening; querying 48h every poll tick created severe overhead.
 * All queries execute off the Main thread using ioDispatcher (defaults to Dispatchers.IO).
 */
class UsageStatsReader(
    private val context: Context,
    private val timeSource: TimeSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    companion object {
        /**
         * Lookback duration: 3 hours in millis.
         *
         * Sufficient to capture any ACTIVITY_RESUMED events from the late evening
         * before midnight that are still active across the midnight boundary,
         * without repeatedly loading multi-day historical event logs.
         */
        const val LOOKBACK_MILLIS: Long = 3L * 60 * 60 * 1000
    }

    /**
     * Read usage events for the current day and calculate foreground millis
     * for the given [packageName].
     *
     * Accounting window: local midnight -> now.
     * Query window: local midnight - LOOKBACK -> now.
     *
     * Returns foreground milliseconds for the package today.
     * Runs off the Main thread on Dispatchers.IO.
     */
    suspend fun readTodayUsageMillis(packageName: String): Long = withContext(ioDispatcher) {
        val nowMillis = timeSource.nowMillis()
        val zone = timeSource.zone()
        val midnightMillis = TimeMath.localMidnightMillis(nowMillis, zone)
        val queryStart = midnightMillis - LOOKBACK_MILLIS

        val events = queryEvents(queryStart, nowMillis)
        UsageCalculator.foregroundMillis(events, packageName, midnightMillis, nowMillis)
    }

    /**
     * Read usage events once for all specified packages and calculate foreground millis.
     * Avoids querying the event window multiple times on each monitor tick.
     * Runs off the Main thread on ioDispatcher.
     */
    suspend fun readTodayUsageMillisBatch(packageNames: Set<String>): Map<String, Long> = withContext(ioDispatcher) {
        if (packageNames.isEmpty()) return@withContext emptyMap()
        val nowMillis = timeSource.nowMillis()
        val zone = timeSource.zone()
        val midnightMillis = TimeMath.localMidnightMillis(nowMillis, zone)
        val queryStart = midnightMillis - LOOKBACK_MILLIS

        val events = queryEvents(queryStart, nowMillis)
        packageNames.associateWith { pkg ->
            UsageCalculator.foregroundMillis(events, pkg, midnightMillis, nowMillis)
        }
    }

    /**
     * Query raw usage events between [beginMillis] and [endMillis],
     * mapped to [UsageEventRecord].
     */
    fun queryEvents(beginMillis: Long, endMillis: Long): List<UsageEventRecord> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()

        val usageEvents = try {
            usm.queryEvents(beginMillis, endMillis)
        } catch (_: SecurityException) {
            return emptyList()
        } ?: return emptyList()

        val records = mutableListOf<UsageEventRecord>()
        val event = UsageEvents.Event()

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            val eventType = mapEventType(event.eventType) ?: continue
            records.add(
                UsageEventRecord(
                    packageName = event.packageName,
                    className = event.className,
                    timestampMillis = event.timeStamp,
                    eventType = eventType
                )
            )
        }

        return records
    }

    /**
     * Check if the app has usage stats access via AppOpsManager.
     */
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
            ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun mapEventType(androidEventType: Int): EventType? {
        return when (androidEventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> EventType.ACTIVITY_RESUMED
            UsageEvents.Event.ACTIVITY_PAUSED -> EventType.ACTIVITY_PAUSED
            UsageEvents.Event.ACTIVITY_STOPPED -> EventType.ACTIVITY_STOPPED
            UsageEvents.Event.DEVICE_SHUTDOWN -> EventType.DEVICE_SHUTDOWN
            else -> null
        }
    }
}
