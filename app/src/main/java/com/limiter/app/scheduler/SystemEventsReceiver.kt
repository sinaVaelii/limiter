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
        val app = context.applicationContext as? LimiterApp ?: return
        val coordinator = app.lockCoordinator ?: return

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                processEvent(
                    context = context,
                    intent = intent,
                    coordinator = coordinator,
                    scheduler = app.timeWindowScheduler,
                    isDeviceOwner = app.isDeviceOwner(),
                    scope = app.applicationScope,
                    pendingResult = goAsync()
                )
            }
        }
    }

    companion object {
        fun processEvent(
            context: Context? = null,
            intent: Intent,
            coordinator: com.limiter.app.policy.LockCoordinator,
            scheduler: TimeWindowScheduler?,
            isDeviceOwner: Boolean,
            scope: kotlinx.coroutines.CoroutineScope,
            pendingResult: PendingResult? = null,
            onFinish: (() -> Unit)? = null,
            timeoutMillis: Long = 8_000L
        ): kotlinx.coroutines.Job {
            val finished = AtomicBoolean(false)
            fun safeLogW(msg: String, tr: Throwable? = null) {
                try {
                    if (tr != null) android.util.Log.w("Limiter", msg, tr) else android.util.Log.w("Limiter", msg)
                } catch (_: Throwable) {}
            }
            fun safeLogE(msg: String, tr: Throwable? = null) {
                try {
                    if (tr != null) android.util.Log.e("Limiter", msg, tr) else android.util.Log.e("Limiter", msg)
                } catch (_: Throwable) {}
            }

            fun finishOnce() {
                if (finished.compareAndSet(false, true)) {
                    try {
                        pendingResult?.finish()
                    } catch (t: Throwable) {
                        safeLogW("Error finishing pending result in SystemEventsReceiver", t)
                    }
                    try {
                        onFinish?.invoke()
                    } catch (t: Throwable) {
                        safeLogW("Error invoking onFinish in SystemEventsReceiver", t)
                    }
                }
            }

            val action = try { intent.action } catch (_: Throwable) { null }
            return try {
                val job = scope.launch {
                    try {
                        kotlinx.coroutines.withTimeout(timeoutMillis) {
                            // After app update, clear and rebuild alarms
                            if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                                scheduler?.cancelAll()
                            }

                            // Invalidate cached critical packages on package events
                            if (action == Intent.ACTION_MY_PACKAGE_REPLACED ||
                                action == Intent.ACTION_PACKAGE_ADDED ||
                                action == Intent.ACTION_PACKAGE_REMOVED ||
                                action == Intent.ACTION_PACKAGE_REPLACED ||
                                action == Intent.ACTION_PACKAGE_CHANGED
                            ) {
                                (context?.applicationContext as? com.limiter.app.LimiterApp)?.criticalPackageResolver?.invalidateCache()
                            }

                            // Reconcile and reschedule
                            coordinator.reconcile()
                            scheduler?.reschedule()

                            // Ensure monitoring where allowed per AGENTS.md §43
                            if (isDeviceOwner && context != null) {
                                try {
                                    com.limiter.app.service.UsageMonitorService.start(context)
                                } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
                                    safeLogE("Foreground service start not allowed in SystemEventsReceiver", e)
                                } catch (e: Exception) {
                                    safeLogW("Could not start UsageMonitorService from SystemEventsReceiver", e)
                                }
                            }
                        }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        safeLogE("Timeout (${timeoutMillis}ms) processing system broadcast: ${action ?: "unknown"}", e)
                    } catch (t: Throwable) {
                        safeLogE("Error processing system broadcast: ${action ?: "unknown"}", t)
                    } finally {
                        finishOnce()
                    }
                }
                job.invokeOnCompletion { finishOnce() }
                job
            } catch (t: Throwable) {
                safeLogE("Failed to schedule coroutine in SystemEventsReceiver", t)
                finishOnce()
                throw t
            }
        }
    }
}
