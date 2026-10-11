package com.limiter.app.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.limiter.app.model.UsageEventRecord
import com.limiter.app.model.UsageEventRecord.EventType
import com.limiter.app.time.TimeMath
import com.limiter.app.time.TimeSource

/**
 * Reads usage events from the Android UsageStatsManager and maps
 * them to the internal [UsageEventRecord] model.
 *
 * Lookback: Queries from the previous local day's beginning (48 hours
 * before local midnight) to catch activities resumed before midnight
 * that remained active across the boundary.
 *
 * Documented lookback: 48 hours before the accounting window start.
 * This covers the previous day entirely plus a safety margin for
 * activities that might span across day boundaries.
 */
class UsageStatsReader(
    private val context: Context,
    private val timeSource: TimeSource
) {

    companion object {
        /**
         * Lookback duration: 48 hours in millis.
         *
         * This ensures we capture ACTIVITY_RESUMED events from the previous
         * day that may still be active across midnight. The previous day's
         * beginning plus a full day of buffer accounts for edge cases in
         * timezone transitions and DST changes.
         */
        const val LOOKBACK_MILLIS: Long = 48L * 60 * 60 * 1000
    }

    /**
     * Read usage events for the current day and calculate foreground millis
     * for the given [packageName].
     *
     * Accounting window: local midnight -> now.
     * Query window: local midnight - LOOKBACK -> now.
     *
     * Returns foreground milliseconds for the package today.
     */
    fun readTodayUsageMillis(packageName: String): Long {
        val nowMillis = timeSource.nowMillis()
        val zone = timeSource.zone()
        val midnightMillis = TimeMath.localMidnightMillis(nowMillis, zone)
        val queryStart = midnightMillis - LOOKBACK_MILLIS

        val events = queryEvents(queryStart, nowMillis)
        return UsageCalculator.foregroundMillis(events, packageName, midnightMillis, nowMillis)
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
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
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
