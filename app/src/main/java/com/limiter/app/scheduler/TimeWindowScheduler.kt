package com.limiter.app.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.limiter.app.data.LockStateStore
import com.limiter.app.data.SettingsRepository
import com.limiter.app.model.LockRecord
import com.limiter.app.model.LockState
import com.limiter.app.time.TimeMath
import com.limiter.app.time.TimeSource
import kotlinx.coroutines.flow.first

/**
 * Alarm scheduler per AGENTS.md §38-42.
 *
 * Schedules at most:
 *   - one next-hide alarm per effective target
 *   - one earliest-unlock/retry alarm
 *   - one next-midnight alarm
 *
 * Uses SCHEDULE_EXACT_ALARM where available, falls back to
 * setAndAllowWhileIdle if exact alarms are not granted.
 *
 * PendingIntents use FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT.
 * Uses explicit component intents with distinct action/data URIs.
 */
/**
 * Result of an individual alarm scheduling operation.
 */
sealed interface AlarmScheduleResult {
    data class ScheduledExact(val triggerMillis: Long) : AlarmScheduleResult
    data class ScheduledInexact(val triggerMillis: Long) : AlarmScheduleResult
    data class Failed(val triggerMillis: Long, val cause: Throwable) : AlarmScheduleResult
}

/**
 * Diagnostic report of the latest reschedule pass.
 * Exposes whether retry and other alarms were successfully scheduled or failed.
 */
data class RescheduleReport(
    val hideAlarms: Map<String, AlarmScheduleResult>,
    val unlockOrRetryAlarm: AlarmScheduleResult?,
    val midnightAlarm: AlarmScheduleResult
) {
    val isRetryScheduled: Boolean
        get() = unlockOrRetryAlarm is AlarmScheduleResult.ScheduledExact ||
                unlockOrRetryAlarm is AlarmScheduleResult.ScheduledInexact

    val hasAnyFailures: Boolean
        get() = hideAlarms.values.any { it is AlarmScheduleResult.Failed } ||
                unlockOrRetryAlarm is AlarmScheduleResult.Failed ||
                midnightAlarm is AlarmScheduleResult.Failed
}

/**
 * Interface abstracting AlarmManager operations for testability, isolation, and failure reporting.
 */
interface AlarmDispatcher {
    fun canScheduleExactAlarms(): Boolean
    fun scheduleExact(triggerMillis: Long, intent: Intent, requestCode: Int)
    fun scheduleInexact(triggerMillis: Long, intent: Intent, requestCode: Int)
    fun cancel(intent: Intent, requestCode: Int)
}

/**
 * Interface abstracting Intent creation for testability and isolation.
 */
interface IntentFactory {
    fun createHideIntent(pkg: String): Intent
    fun createUnlockIntent(): Intent
    fun createMidnightIntent(): Intent
    fun createRetryIntent(): Intent
}

/**
 * Production implementation of AlarmDispatcher using Android system services.
 */
class PlatformAlarmDispatcher(private val context: Context) : AlarmDispatcher {
    private val alarmManager: AlarmManager? by lazy {
        context.getSystemService(AlarmManager::class.java)
    }

    override fun canScheduleExactAlarms(): Boolean {
        return try {
            alarmManager?.canScheduleExactAlarms() ?: false
        } catch (_: Exception) {
            false
        }
    }

    override fun scheduleExact(triggerMillis: Long, intent: Intent, requestCode: Int) {
        val am = alarmManager ?: throw IllegalStateException("AlarmManager system service unavailable")
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pending)
    }

    override fun scheduleInexact(triggerMillis: Long, intent: Intent, requestCode: Int) {
        val am = alarmManager ?: throw IllegalStateException("AlarmManager system service unavailable")
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pending)
    }

    override fun cancel(intent: Intent, requestCode: Int) {
        val am = alarmManager ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.cancel(pending)
    }
}

/**
 * Production implementation of IntentFactory using component intents and explicit URIs.
 */
class PlatformIntentFactory(private val context: Context) : IntentFactory {
    override fun createHideIntent(pkg: String): Intent {
        return Intent(context, AlarmReceiver::class.java).apply {
            action = TimeWindowScheduler.ACTION_HIDE
            data = Uri.parse("limiter://hide/${Uri.encode(pkg)}")
        }
    }

    override fun createUnlockIntent(): Intent {
        return Intent(context, AlarmReceiver::class.java).apply {
            action = TimeWindowScheduler.ACTION_UNLOCK
            data = Uri.parse("limiter://unlock")
        }
    }

    override fun createMidnightIntent(): Intent {
        return Intent(context, AlarmReceiver::class.java).apply {
            action = TimeWindowScheduler.ACTION_MIDNIGHT
            data = Uri.parse("limiter://midnight")
        }
    }

    override fun createRetryIntent(): Intent {
        return Intent(context, AlarmReceiver::class.java).apply {
            action = TimeWindowScheduler.ACTION_RETRY
            data = Uri.parse("limiter://retry")
        }
    }
}

class TimeWindowScheduler(
    private val context: Context? = null,
    private val settingsRepository: SettingsRepository,
    private val lockStateStore: LockStateStore,
    private val timeSource: TimeSource,
    private val alarmDispatcher: AlarmDispatcher = PlatformAlarmDispatcher(context!!),
    private val intentFactory: IntentFactory = PlatformIntentFactory(context!!)
) {

    companion object {
        const val ACTION_HIDE = "com.limiter.app.action.HIDE"
        const val ACTION_UNLOCK = "com.limiter.app.action.UNLOCK"
        const val ACTION_MIDNIGHT = "com.limiter.app.action.MIDNIGHT"
        const val ACTION_RETRY = "com.limiter.app.action.RETRY"

        const val REQUEST_MIDNIGHT = 10000
        const val REQUEST_UNLOCK = 10001
        const val REQUEST_RETRY = 10002
        const val REQUEST_HIDE_BASE = 20000

        fun calculateHideRequestCode(pkg: String): Int {
            return REQUEST_HIDE_BASE + (pkg.hashCode() and 0x3FFF)
        }

        data class EarliestUnlockPlan(
            val triggerMillis: Long,
            val isRetry: Boolean,
            val requestCode: Int,
            val action: String
        )

        fun calculateEarliestUnlockPlan(
            lockRecords: Collection<LockRecord>,
            now: Long
        ): EarliestUnlockPlan? {
            var earliestUnlockMillis: Long? = null
            var isRetryAlarm = false
            for (record in lockRecords) {
                val relevantTime = when (val state = record.state) {
                    is LockState.LockedUntil -> {
                        val retryAt = record.nextUnlockRetryAtMillis
                        when {
                            retryAt != null && now >= state.untilEpochMillis -> retryAt
                            else -> state.untilEpochMillis
                        }
                    }
                    is LockState.ReleasePending -> {
                        record.nextUnlockRetryAtMillis ?: now
                    }
                    is LockState.Unlocked -> {
                        record.nextUnlockRetryAtMillis
                    }
                } ?: continue

                if (earliestUnlockMillis == null || relevantTime < earliestUnlockMillis) {
                    earliestUnlockMillis = relevantTime
                    isRetryAlarm = record.state is LockState.ReleasePending ||
                            (record.nextUnlockRetryAtMillis != null && relevantTime == record.nextUnlockRetryAtMillis)
                }
            }

            return earliestUnlockMillis?.let { trigger ->
                val action = if (isRetryAlarm) ACTION_RETRY else ACTION_UNLOCK
                val reqCode = if (isRetryAlarm) REQUEST_RETRY else REQUEST_UNLOCK
                EarliestUnlockPlan(trigger, isRetryAlarm, reqCode, action)
            }
        }
    }

    var lastRescheduleReport: RescheduleReport? = null
        private set

    /**
     * Reschedule all alarms based on current state.
     * Called after reconciliation and system events.
     * Returns a RescheduleReport detailing whether exact/inexact scheduling succeeded or failed.
     */
    suspend fun reschedule(): RescheduleReport {
        val now = timeSource.nowMillis()
        val zone = timeSource.zone()
        val targets = settingsRepository.snapshotTargets()
        val lockRecords = lockStateStore.snapshot()

        // 1. Schedule next-hide alarm per effective target with deterministic request codes
        val hideResults = mutableMapOf<String, AlarmScheduleResult>()
        for ((pkg, config) in targets) {
            val hideMillis = TimeMath.nextOccurrence(now, zone, config.hideAtMinuteOfDay)
            val res = scheduleAlarm(
                triggerMillis = hideMillis,
                intent = intentFactory.createHideIntent(pkg),
                requestCode = getHideRequestCode(pkg)
            )
            hideResults[pkg] = res
        }

        // 2. Schedule earliest unlock/retry alarm (including ReleasePending and backoff retries)
        val unlockPlan = calculateEarliestUnlockPlan(lockRecords.values, now)
        val unlockResult = if (unlockPlan != null) {
            val intent = if (unlockPlan.isRetry) intentFactory.createRetryIntent() else intentFactory.createUnlockIntent()
            scheduleAlarm(
                triggerMillis = unlockPlan.triggerMillis,
                intent = intent,
                requestCode = unlockPlan.requestCode
            )
        } else {
            null
        }

        // 3. Schedule midnight alarm
        val nextMidnight = TimeMath.nextLocalMidnightMillis(now, zone)
        val midnightResult = scheduleAlarm(
            triggerMillis = nextMidnight,
            intent = intentFactory.createMidnightIntent(),
            requestCode = REQUEST_MIDNIGHT
        )

        val report = RescheduleReport(
            hideAlarms = hideResults,
            unlockOrRetryAlarm = unlockResult,
            midnightAlarm = midnightResult
        )
        lastRescheduleReport = report
        return report
    }

    /**
     * Cancel all scheduled global alarms and hide alarms for known targets.
     */
    suspend fun cancelAll() {
        // Cancel midnight
        alarmDispatcher.cancel(intentFactory.createMidnightIntent(), REQUEST_MIDNIGHT)
        // Cancel unlock
        alarmDispatcher.cancel(intentFactory.createUnlockIntent(), REQUEST_UNLOCK)
        // Cancel retry
        alarmDispatcher.cancel(intentFactory.createRetryIntent(), REQUEST_RETRY)
        // Cancel hide alarms for current targets
        val targets = settingsRepository.snapshotTargets()
        for (pkg in targets.keys) {
            cancelHideAlarm(pkg)
        }
    }

    /**
     * Cancel hide alarm for a specific target package.
     */
    fun cancelHideAlarm(pkg: String) {
        alarmDispatcher.cancel(intentFactory.createHideIntent(pkg), getHideRequestCode(pkg))
    }

    /**
     * Generate deterministic requestCode for hide alarms based on package name.
     */
    fun getHideRequestCode(pkg: String): Int {
        return calculateHideRequestCode(pkg)
    }

    private fun scheduleAlarm(triggerMillis: Long, intent: Intent, requestCode: Int): AlarmScheduleResult {
        var exactFailure: Throwable? = null
        if (alarmDispatcher.canScheduleExactAlarms()) {
            try {
                alarmDispatcher.scheduleExact(
                    triggerMillis = triggerMillis,
                    intent = intent,
                    requestCode = requestCode
                )
                return AlarmScheduleResult.ScheduledExact(triggerMillis)
            } catch (e: SecurityException) {
                exactFailure = e
                safeLogW("scheduleExact rejected for reqCode=$requestCode; falling back to scheduleInexact", e)
            } catch (e: Exception) {
                exactFailure = e
                safeLogE("Unexpected error calling scheduleExact for reqCode=$requestCode", e)
            }
        }
        return try {
            alarmDispatcher.scheduleInexact(
                triggerMillis = triggerMillis,
                intent = intent,
                requestCode = requestCode
            )
            AlarmScheduleResult.ScheduledInexact(triggerMillis)
        } catch (e: Exception) {
            safeLogE("Failed to schedule alarm for reqCode=$requestCode (triggerMillis=$triggerMillis)", e)
            AlarmScheduleResult.Failed(triggerMillis, exactFailure ?: e)
        }
    }

    private fun safeLogW(msg: String, tr: Throwable? = null) {
        try {
            if (tr != null) android.util.Log.w("Limiter", msg, tr) else android.util.Log.w("Limiter", msg)
        } catch (_: Throwable) {}
    }

    private fun safeLogE(msg: String, tr: Throwable? = null) {
        try {
            if (tr != null) android.util.Log.e("Limiter", msg, tr) else android.util.Log.e("Limiter", msg)
        } catch (_: Throwable) {}
    }
}
