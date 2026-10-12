package com.limiter.app.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.limiter.app.LimiterApp
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * System events receiver per AGENTS.md §43.
 *
 * Handles:
 *   BOOT_COMPLETED
 *   MY_PACKAGE_REPLACED
 *   TIME_SET
 *   TIMEZONE_CHANGED
 *
 * On all applicable events: reconcile, reschedule, ensure monitoring.
 *
 * After app update: clear/rebuild scheduled alarms because old PendingIntents
 * must not control obsolete configuration.
 *
 * Does NOT handle LOCKED_BOOT_COMPLETED (direct-boot out of scope).
 */
class SystemEventsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as LimiterApp
        val coordinator = app.lockCoordinator ?: return

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                val pendingResult = goAsync()
                val finished = AtomicBoolean(false)
                fun finishOnce() {
                    if (finished.compareAndSet(false, true)) {
                        try {
                            pendingResult.finish()
                        } catch (t: Throwable) {
                            android.util.Log.w("Limiter", "Error finishing pending result in SystemEventsReceiver", t)
                        }
                    }
                }

                try {
                    val job = app.applicationScope.launch {
                        try {
                            // After app update, clear and rebuild alarms
                            if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                                app.timeWindowScheduler?.cancelAll()
                            }

                            // Reconcile and reschedule
                            coordinator.reconcile()
                            app.timeWindowScheduler?.reschedule()

                            // Ensure monitoring where allowed per AGENTS.md §43
                            if (app.isDeviceOwner()) {
                                com.limiter.app.service.UsageMonitorService.start(context)
                            }
                        } catch (t: Throwable) {
                            android.util.Log.e("Limiter", "Error processing system event: ${intent.action}", t)
                        }
                    }
                    job.invokeOnCompletion { finishOnce() }
                } catch (t: Throwable) {
                    android.util.Log.e("Limiter", "Failed to schedule coroutine in SystemEventsReceiver", t)
                    finishOnce()
                }
            }
        }
    }
}
