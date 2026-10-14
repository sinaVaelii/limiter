package com.limiter.app.data

import android.content.Context
import android.os.Build
import com.limiter.app.dpm.DpmGateway

/**
 * Container for device system and permission status.
 */
data class SystemStatus(
    val isDeviceOwner: Boolean = false,
    val hasUsageAccess: Boolean = false,
    val hasNotificationPermission: Boolean = false,
    val hasExactAlarmPermission: Boolean = false,
    val isUninstallBlocked: Boolean = false,
    val isUserControlDisabled: Boolean = false
)

/**
 * Interface to check device and permission status.
 * Faked in JVM tests; implemented using Android framework in production.
 */
interface SystemStatusChecker {
    fun checkStatus(): SystemStatus
}

/**
 * Production implementation using Android system services.
 */
class DeviceSystemStatusChecker(
    private val context: Context,
    private val dpmGateway: DpmGateway,
    private val usageStatsReader: UsageStatsReader
) : SystemStatusChecker {

    override fun checkStatus(): SystemStatus {
        val isDeviceOwner = dpmGateway.isDeviceOwner()
        val hasUsage = usageStatsReader.hasUsageAccess()

        val hasNotif = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

        val am = context.getSystemService(android.app.AlarmManager::class.java)
        val hasAlarm = am?.canScheduleExactAlarms() ?: false

        val isUninstallBlocked = dpmGateway.isUninstallBlocked(context.packageName).getOrDefault(false)
        val isUserControlDisabled = dpmGateway.getUserControlDisabledPackages().contains(context.packageName)

        return SystemStatus(
            isDeviceOwner = isDeviceOwner,
            hasUsageAccess = hasUsage,
            hasNotificationPermission = hasNotif,
            hasExactAlarmPermission = hasAlarm,
            isUninstallBlocked = isUninstallBlocked,
            isUserControlDisabled = isUserControlDisabled
        )
    }
}
