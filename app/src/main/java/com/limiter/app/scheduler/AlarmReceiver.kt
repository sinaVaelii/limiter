package com.limiter.app.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.limiter.app.LimiterApp
import com.limiter.app.policy.LockCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Alarm receiver.
 *
 * Delegates to LockCoordinator — never directly mutates lock state.
 *
 * PendingIntents use explicit component intents with distinct action/data URIs:
 *   limiter://hide/<encoded-pkg>
 *   limiter://unlock
 *   limiter://retry
 *   limiter://midnight
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? LimiterApp ?: return
        val coordinator = app.lockCoordinator

        processAlarm(
            context = context,
            intent = intent,
            coordinator = coordinator,
            scheduler = app.timeWindowScheduler,
            isDeviceOwner = app.isDeviceOwner(),
            scope = app.applicationScope,
            pendingResult = goAsync()
        )
    }

    companion object {
        fun processAlarm(
            context: Context? = null,
            intent: Intent,
            coordinator: LockCoordinator,
            scheduler: TimeWindowScheduler?,
            isDeviceOwner: Boolean,
            scope: CoroutineScope,
            pendingResult: PendingResult? = null,
            onFinish: (() -> Unit)? = null,
            timeoutMillis: Long = 8_000L
        ): Job {
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
                        safeLogW("Error finishing pending result in AlarmReceiver", t)
                    }
                    try {
                        onFinish?.invoke()
                    } catch (t: Throwable) {
                        safeLogW("Error invoking onFinish in AlarmReceiver", t)
                    }
                }
            }

            val action = try { intent.action } catch (_: Throwable) { null }
            return try {
                val job = scope.launch {
                    try {
                        kotlinx.coroutines.withTimeout(timeoutMillis) {
                            coordinator.reconcile()
                            scheduler?.reschedule()
                            if (isDeviceOwner && context != null) {
                                try {
                                    com.limiter.app.service.UsageMonitorService.start(context)
                                } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
                                    safeLogE("Foreground service start not allowed in AlarmReceiver", e)
                                } catch (e: Exception) {
                                    safeLogW("Could not start UsageMonitorService from AlarmReceiver", e)
                                }
                            }
                        }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        safeLogE("Timeout (${timeoutMillis}ms) processing alarm broadcast: ${action ?: "unknown"}", e)
                    } catch (t: Throwable) {
                        safeLogE("Error processing alarm broadcast: ${action ?: "unknown"}", t)
                    } finally {
                        finishOnce()
                    }
                }
                job.invokeOnCompletion { finishOnce() }
                job
            } catch (t: Throwable) {
                safeLogE("Failed to schedule coroutine in AlarmReceiver", t)
                finishOnce()
                throw t
            }
        }
    }
}
