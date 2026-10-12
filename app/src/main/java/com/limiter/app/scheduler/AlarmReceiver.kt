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
 * Alarm receiver per AGENTS.md §42.
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
        val coordinator = app.lockCoordinator ?: return

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
            pendingResult: PendingResult? = null
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
                }
            }

            return try {
                val job = scope.launch {
                    try {
                        coordinator.reconcile()
                        scheduler?.reschedule()
                        if (isDeviceOwner && context != null) {
                            try {
                                com.limiter.app.service.UsageMonitorService.start(context)
                            } catch (e: Exception) {
                                safeLogW("Could not start UsageMonitorService from AlarmReceiver", e)
                            }
                        }
                    } catch (t: Throwable) {
                        safeLogE("Error processing alarm broadcast: ${intent.action}", t)
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
