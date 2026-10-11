package com.limiter.app.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.limiter.app.LimiterApp
import kotlinx.coroutines.launch

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
        val app = context.applicationContext as LimiterApp
        val coordinator = app.lockCoordinator ?: return

        // All alarm actions trigger reconciliation.
        // The coordinator determines what to do based on current state.
        app.applicationScope.launch {
            coordinator.reconcile()
            app.timeWindowScheduler?.reschedule()
            if (app.isDeviceOwner()) {
                com.limiter.app.service.UsageMonitorService.start(context)
            }
        }
    }
}
