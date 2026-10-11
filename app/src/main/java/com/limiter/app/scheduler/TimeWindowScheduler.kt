package com.limiter.app.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.limiter.app.data.LockStateStore
import com.limiter.app.data.SettingsRepository
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
class TimeWindowScheduler(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val lockStateStore: LockStateStore,
    private val timeSource: TimeSource
) {

    companion object {
        const val ACTION_HIDE = "com.limiter.app.action.HIDE"
        const val ACTION_UNLOCK = "com.limiter.app.action.UNLOCK"
        const val ACTION_MIDNIGHT = "com.limiter.app.action.MIDNIGHT"
        const val ACTION_RETRY = "com.limiter.app.action.RETRY"

        private const val REQUEST_MIDNIGHT = 10000
        private const val REQUEST_UNLOCK = 10001
        private const val REQUEST_RETRY = 10002
        private const val REQUEST_HIDE_BASE = 20000
    }

    private val alarmManager: AlarmManager by lazy {
        context.getSystemService(AlarmManager::class.java)
    }

    /**
     * Reschedule all alarms based on current state.
     * Called after reconciliation and system events.
     */
    suspend fun reschedule() {
        val now = timeSource.nowMillis()
        val zone = timeSource.zone()
        val targets = settingsRepository.snapshotTargets()
        val lockRecords = lockStateStore.snapshot()

        // 1. Schedule next-hide alarm per effective target
        var hideIndex = 0
        for ((pkg, config) in targets) {
            val hideMillis = TimeMath.nextOccurrence(now, zone, config.hideAtMinuteOfDay)
            scheduleAlarm(
                triggerMillis = hideMillis,
                intent = createHideIntent(pkg),
                requestCode = REQUEST_HIDE_BASE + hideIndex
            )
            hideIndex++
        }

        // 2. Schedule earliest unlock/retry alarm
        var earliestUnlockMillis: Long? = null
        for ((pkg, record) in lockRecords) {
            val lockedUntil = record.state as? LockState.LockedUntil ?: continue

            // For expired locks with retry, use retry time
            val retryAt = record.nextUnlockRetryAtMillis
            val relevantTime = when {
                retryAt != null && now >= lockedUntil.untilEpochMillis -> retryAt
                else -> lockedUntil.untilEpochMillis
            }

            if (earliestUnlockMillis == null || relevantTime < earliestUnlockMillis) {
                earliestUnlockMillis = relevantTime
            }
        }

        if (earliestUnlockMillis != null) {
            scheduleAlarm(
                triggerMillis = earliestUnlockMillis,
                intent = createUnlockIntent(),
                requestCode = REQUEST_UNLOCK
            )
        }

        // 3. Schedule midnight alarm
        val nextMidnight = TimeMath.nextLocalMidnightMillis(now, zone)
        scheduleAlarm(
            triggerMillis = nextMidnight,
            intent = createMidnightIntent(),
            requestCode = REQUEST_MIDNIGHT
        )
    }

    /**
     * Cancel all scheduled alarms. Called before rebuild.
     */
    fun cancelAll() {
        // Cancel midnight
        alarmManager.cancel(createPendingIntent(createMidnightIntent(), REQUEST_MIDNIGHT))
        // Cancel unlock
        alarmManager.cancel(createPendingIntent(createUnlockIntent(), REQUEST_UNLOCK))
        // Cancel retry
        alarmManager.cancel(createPendingIntent(createRetryIntent(), REQUEST_RETRY))
        // Hide alarms are package-indexed; we can't easily cancel all without tracking
    }

    private fun scheduleAlarm(triggerMillis: Long, intent: Intent, requestCode: Int) {
        val pending = createPendingIntent(intent, requestCode)
        if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerMillis,
                pending
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerMillis,
                pending
            )
        }
    }

    private fun canScheduleExact(): Boolean {
        return try {
            alarmManager.canScheduleExactAlarms()
        } catch (_: Exception) {
            false
        }
    }

    private fun createHideIntent(pkg: String): Intent {
        return Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_HIDE
            data = Uri.parse("limiter://hide/${Uri.encode(pkg)}")
        }
    }

    private fun createUnlockIntent(): Intent {
        return Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_UNLOCK
            data = Uri.parse("limiter://unlock")
        }
    }

    private fun createMidnightIntent(): Intent {
        return Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_MIDNIGHT
            data = Uri.parse("limiter://midnight")
        }
    }

    private fun createRetryIntent(): Intent {
        return Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_RETRY
            data = Uri.parse("limiter://retry")
        }
    }

    private fun createPendingIntent(intent: Intent, requestCode: Int): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
