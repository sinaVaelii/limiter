package com.limiter.app

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import com.limiter.app.admin.LimiterAdminReceiver
import com.limiter.app.data.CriticalPackageResolver
import com.limiter.app.data.DataStoreLockStateStore
import com.limiter.app.data.DeviceCriticalPackageResolver
import com.limiter.app.data.LockStateStore
import com.limiter.app.data.SettingsRepository
import com.limiter.app.data.UsageSnapshotStore
import com.limiter.app.data.UsageStatsReader
import com.limiter.app.data.lockStateDataStore
import com.limiter.app.data.settingsDataStore
import com.limiter.app.data.usageSnapshotDataStore
import com.limiter.app.dpm.AppFreezer
import com.limiter.app.dpm.AppFreezerApi
import com.limiter.app.dpm.DeviceDpmGateway
import com.limiter.app.dpm.DpmGateway
import com.limiter.app.policy.LockCoordinator
import com.limiter.app.scheduler.TimeWindowScheduler
import com.limiter.app.service.UsageMonitorService
import com.limiter.app.time.SystemTimeSource
import com.limiter.app.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Lightweight service locator with lazy singletons and
 * application-scoped coroutine scope.
 *
 * No blocking or heavy initialization in onCreate().
 * No dependency injection framework.
 */
class LimiterApp : Application() {

    /** Application-scoped coroutine scope. */
    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    val timeSource: TimeSource by lazy { SystemTimeSource() }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(settingsDataStore)
    }

    val usageSnapshotStore: UsageSnapshotStore by lazy {
        UsageSnapshotStore(usageSnapshotDataStore)
    }

    val usageStatsReader: UsageStatsReader by lazy {
        UsageStatsReader(this, timeSource)
    }

    val criticalPackageResolver: CriticalPackageResolver by lazy {
        DeviceCriticalPackageResolver(this)
    }

    val appDiscoveryRepository: com.limiter.app.data.AppDiscoveryRepository by lazy {
        com.limiter.app.data.DeviceAppDiscoveryRepository(this, criticalPackageResolver)
    }

    val systemStatusChecker: com.limiter.app.data.SystemStatusChecker by lazy {
        com.limiter.app.data.DeviceSystemStatusChecker(this, dpmGateway, usageStatsReader)
    }

    // ---- Stage 3 additions ----

    val dpmGateway: DpmGateway by lazy {
        DeviceDpmGateway(this)
    }

    val appFreezer: AppFreezerApi by lazy {
        AppFreezer(dpmGateway)
    }

    val lockStateStore: LockStateStore by lazy {
        DataStoreLockStateStore(lockStateDataStore)
    }

    /**
     * LockCoordinator — the authoritative lock transition boundary.
     */
    val lockCoordinator: LockCoordinator by lazy {
        LockCoordinator(
            gateway = dpmGateway,
            freezer = appFreezer,
            lockStateStore = lockStateStore,
            settingsRepository = settingsRepository,
            criticalPackageResolver = criticalPackageResolver,
            timeSource = timeSource
        )
    }

    /**
     * Limit-reached callback for the monitor service.
     */
    var limitReachedCallback: UsageMonitorService.LimitReachedCallback? = null
        get() = lockCoordinator

    val timeWindowScheduler: TimeWindowScheduler by lazy {
        TimeWindowScheduler(
            context = this,
            settingsRepository = settingsRepository,
            lockStateStore = lockStateStore,
            timeSource = timeSource
        )
    }

    private val adminComponentName: ComponentName by lazy {
        ComponentName(this, LimiterAdminReceiver::class.java)
    }

    /**
     * Check if this app is the Device Owner.
     */
    fun isDeviceOwner(): Boolean {
        val dpm = getSystemService(DevicePolicyManager::class.java) ?: return false
        return dpm.isDeviceOwnerApp(packageName)
    }

    override fun onCreate() {
        super.onCreate()
        // No blocking or heavy initialization on main thread.
        applicationScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            if (isDeviceOwner()) {
                lockCoordinator.reconcile()
                timeWindowScheduler.reschedule()
                try {
                    com.limiter.app.service.UsageMonitorService.start(this@LimiterApp)
                } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
                    android.util.Log.e("Limiter", "Foreground service start not allowed in LimiterApp.onCreate", e)
                } catch (e: Exception) {
                    android.util.Log.e("Limiter", "Failed to start UsageMonitorService in LimiterApp.onCreate", e)
                }
            }
        }
    }
}
