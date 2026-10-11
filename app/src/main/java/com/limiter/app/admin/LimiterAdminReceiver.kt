package com.limiter.app.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Device Admin receiver for Limiter.
 *
 * This receiver is registered as the Device Owner component:
 *   com.limiter.app/.admin.LimiterAdminReceiver
 *
 * Stage 1: Placeholder — no enforcement logic yet.
 */
class LimiterAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
    }
}
