package com.limiter.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.limiter.app.LimiterApp
import com.limiter.app.model.UsageSnapshot
import com.limiter.app.policy.PollPlanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that monitors usage for all target packages.
 *
 * On each tick (spec section 17):
 *   1. Verify Device Owner
 *   2. Verify Usage Access
 *   3. Read usage
 *   4. Publish snapshot
 *   5. For every effective target: if usedMillis >= limit → callback onLimitReached
 *   6. Wait according to PollPlanner
 *
 * The service never directly freezes a package.
 * No lock semantics belong inside the service.
 *
 * Uses foregroundServiceType="systemExempted" for Device Owner on API 34+.
 */
class UsageMonitorService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "limiter_monitor"
        private const val NOTIFICATION_ID = 1
        private const val NO_USAGE_ACCESS_RETRY_MS = 60_000L

        val LOOP_ERROR_BACKOFF_MS = longArrayOf(5_000L, 10_000L, 30_000L, 60_000L)

        fun calculateLoopBackoff(consecutiveFailures: Int): Long {
            if (consecutiveFailures <= 0) return 0L
            val index = (consecutiveFailures - 1).coerceIn(0, LOOP_ERROR_BACKOFF_MS.size - 1)
            return LOOP_ERROR_BACKOFF_MS[index]
        }

        fun start(context: Context) {
            try {
                val intent = Intent(context, UsageMonitorService::class.java)
                context.startForegroundService(intent)
            } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
                android.util.Log.e("Limiter", "Foreground service start not allowed in UsageMonitorService.start", e)
            } catch (e: Exception) {
                android.util.Log.e("Limiter", "Failed to start UsageMonitorService", e)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, UsageMonitorService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
                android.util.Log.e("Limiter", "Failed to stop UsageMonitorService", e)
            }
        }
    }

    /**
     * Callback interface for limit-reached events.
     * Stage 2: no-op implementation. Stage 3 will wire this to LockCoordinator.
     */
    interface LimitReachedCallback {
        suspend fun onLimitReached(packageName: String, usedMillis: Long)
    }

    private var monitorJob: Job? = null
    internal var consecutiveLoopFailures: Int = 0
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (monitorJob?.isActive == true) {
            return START_STICKY
        }

        monitorJob = lifecycleScope.launch {
            monitorLoop()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        super.onDestroy()
    }

    private suspend fun monitorLoop() {
        val app = application as LimiterApp
        val reader = app.usageStatsReader
        val snapshotStore = app.usageSnapshotStore
        val settingsRepo = app.settingsRepository
        val callback = app.limitReachedCallback

        while (lifecycleScope.coroutineContext[Job]?.isActive == true) {
            try {
                // 1. Verify Device Owner
                if (!app.isDeviceOwner()) {
                    // Publish DeviceOwnerRequired status — don't fabricate enforcement
                    snapshotStore.clear()
                    delay(NO_USAGE_ACCESS_RETRY_MS)
                    continue
                }

                // 2. Verify Usage Access
                if (!reader.hasUsageAccess()) {
                    snapshotStore.clear()
                    delay(NO_USAGE_ACCESS_RETRY_MS)
                    continue
                }

                // 3 & 4 & 5. Read usage, publish snapshots, check limits
                val targets = settingsRepo.snapshotTargets()
                var minRemaining = Long.MAX_VALUE

                val allSnapshots = mutableMapOf<String, UsageSnapshot.Available>()
                val usageMap = reader.readTodayUsageMillisBatch(targets.keys)
                var anyLimitReached = false

                for ((pkg, config) in targets) {
                    val usedMillis = usageMap[pkg] ?: 0L
                    val effectiveDailyLimitMinutes = minOf(config.dailyLimitMinutes, com.limiter.app.policy.SettingsPolicy.MAX_DAILY_LIMIT_MINUTES)
                    val limitMillis = effectiveDailyLimitMinutes * 60_000L
                    val remainingMillis = (limitMillis - usedMillis).coerceAtLeast(0)

                    val snapshot = UsageSnapshot.Available(
                        packageName = pkg,
                        usedMillis = usedMillis,
                        remainingMillis = remainingMillis,
                        limitMinutes = effectiveDailyLimitMinutes
                    )
                    allSnapshots[pkg] = snapshot

                    if (remainingMillis < minRemaining) {
                        minRemaining = remainingMillis
                    }

                    // 5. Check limit: usedMillis >= limitMinutes * 60_000L
                    if (usedMillis >= limitMillis) {
                        callback?.onLimitReached(pkg, usedMillis)
                        anyLimitReached = true
                    }
                }

                snapshotStore.publishAll(allSnapshots)

                // 5.5. Watchdog check: reconcile and reschedule if any lock deadline or retry is due
                val currentLockRecords = app.lockStateStore.snapshot()
                val nowMillis = app.timeSource.nowMillis()
                val reconciled = UsageMonitorWatchdog.reconcileIfDue(currentLockRecords, nowMillis) {
                    app.lockCoordinator?.reconcile()
                }

                if (anyLimitReached || reconciled) {
                    app.timeWindowScheduler?.rescheduleIfPlanChanged()
                }

                // Successful poll cycle: reset consecutive failure count
                consecutiveLoopFailures = 0

                // 6. Wait according to PollPlanner
                val delayMs = if (minRemaining == Long.MAX_VALUE) {
                    PollPlanner.nextDelayMillis(Long.MAX_VALUE)
                } else if (minRemaining <= 0) {
                    // All targets reached limit or have 0 remaining; wait 30s instead of spinning at 0ms
                    30_000L
                } else {
                    PollPlanner.nextDelayMillis(minRemaining)
                }

                delay(delayMs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                consecutiveLoopFailures++
                val backoffMs = calculateLoopBackoff(consecutiveLoopFailures)
                android.util.Log.e(
                    "Limiter",
                    "Recovered from unexpected exception in monitorLoop (consecutive failures: $consecutiveLoopFailures, backoff: ${backoffMs}ms)",
                    e
                )
                delay(backoffMs)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Limiter Monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Monitors app usage limits"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Limiter")
            .setContentText("Monitoring app usage")
            .setOngoing(true)
            .build()
    }
}
