package com.limiter.app.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.limiter.app.data.AppDiscoveryRepository
import com.limiter.app.data.CriticalPackageResolver
import com.limiter.app.data.DiscoveredApp
import com.limiter.app.data.InMemoryLockStateStore
import com.limiter.app.data.SettingsRepository
import com.limiter.app.data.SystemStatus
import com.limiter.app.data.SystemStatusChecker
import com.limiter.app.data.UsageSnapshotStore
import com.limiter.app.dpm.AppFreezer
import com.limiter.app.dpm.FakeDpmGateway
import com.limiter.app.model.LockState
import com.limiter.app.model.TargetAppConfig
import com.limiter.app.model.UsageSnapshot
import com.limiter.app.policy.LockCoordinator
import com.limiter.app.time.FakeTimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelR4Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val utc = ZoneId.of("UTC")

    private lateinit var gateway: FakeDpmGateway
    private lateinit var freezer: AppFreezer
    private lateinit var lockStateStore: InMemoryLockStateStore
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var usageSnapshotStore: UsageSnapshotStore
    private lateinit var timeSource: FakeTimeSource
    private lateinit var resolver: FakeCriticalPackageResolver
    private lateinit var discoveryRepo: FakeAppDiscoveryRepository
    private lateinit var coordinator: LockCoordinator
    private lateinit var statusChecker: FakeSystemStatusChecker
    private lateinit var viewModel: MainViewModel

    class FakeCriticalPackageResolver : CriticalPackageResolver {
        val protectedPackages = mutableSetOf<String>()
        val ineligiblePackages = mutableSetOf<String>()

        override fun resolveProtected(): Set<String> = protectedPackages

        override fun isEligibleTarget(packageName: String): Boolean {
            return packageName !in ineligiblePackages && packageName !in protectedPackages
        }
    }

    class FakeAppDiscoveryRepository(
        val allInstalledApps: MutableList<DiscoveredApp> = mutableListOf(),
        private val resolver: CriticalPackageResolver
    ) : AppDiscoveryRepository {
        override suspend fun getEligibleApps(alreadyConfigured: Set<String>): List<DiscoveredApp> {
            val protected = resolver.resolveProtected()
            return allInstalledApps.filter { app ->
                app.packageName !in alreadyConfigured &&
                        app.packageName !in protected &&
                        resolver.isEligibleTarget(app.packageName)
            }.sortedBy { it.label.lowercase() }
        }
    }

    class FakeSystemStatusChecker(
        var status: SystemStatus = SystemStatus(
            isDeviceOwner = true,
            hasUsageAccess = true,
            hasNotificationPermission = true,
            hasExactAlarmPermission = true,
            isUninstallBlocked = true,
            isUserControlDisabled = true
        )
    ) : SystemStatusChecker {
        override fun checkStatus(): SystemStatus = status
    }

    private fun zdtToMillis(hour: Int, minute: Int): Long {
        return ZonedDateTime.of(2024, 6, 15, hour, minute, 0, 0, utc)
            .toInstant().toEpochMilli()
    }

    @Before
    fun setup() {
        gateway = FakeDpmGateway()
        gateway.deviceOwner = true
        freezer = AppFreezer(gateway)
        lockStateStore = InMemoryLockStateStore()

        val settingsFile = tempFolder.newFile("test_settings.preferences_pb")
        val settingsDataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { settingsFile }
        )
        settingsRepository = SettingsRepository(settingsDataStore)

        val usageFile = tempFolder.newFile("test_usage.preferences_pb")
        val usageDataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { usageFile }
        )
        usageSnapshotStore = UsageSnapshotStore(usageDataStore)

        timeSource = FakeTimeSource()
        timeSource.setNowMillis(zdtToMillis(12, 0)) // 12:00 UTC
        timeSource.setZone(utc)

        resolver = FakeCriticalPackageResolver()
        resolver.protectedPackages.addAll(listOf("com.limiter.app", "com.android.systemui", "com.android.settings"))

        discoveryRepo = FakeAppDiscoveryRepository(
            allInstalledApps = mutableListOf(
                DiscoveredApp("com.dv.adm", "Advanced Download Manager"),
                DiscoveredApp("org.telegram.messenger", "Telegram")
            ),
            resolver = resolver
        )

        coordinator = LockCoordinator(
            gateway = gateway,
            freezer = freezer,
            lockStateStore = lockStateStore,
            settingsRepository = settingsRepository,
            criticalPackageResolver = resolver,
            timeSource = timeSource
        )

        statusChecker = FakeSystemStatusChecker()

        Dispatchers.setMain(testDispatcher)

        viewModel = MainViewModel(
            dpmGateway = gateway,
            settingsRepository = settingsRepository,
            lockStateStore = lockStateStore,
            usageSnapshotStore = usageSnapshotStore,
            timeSource = timeSource,
            lockCoordinator = coordinator,
            appDiscoveryRepository = discoveryRepo,
            criticalPackageResolver = resolver,
            systemStatusChecker = statusChecker
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun selfLockActive_whenTargetInsideScheduledWindow() = runTest(testDispatcher) {
        // Target: hide at 23:00 (1380), reveal at 07:00 (420)
        val config = TargetAppConfig("com.dv.adm", 60, 1380, 420)
        settingsRepository.putTarget(config)

        // Set time to 23:30 (inside overnight window)
        timeSource.setNowMillis(zdtToMillis(23, 30))
        viewModel.tickCurrentTime()

        val state = viewModel.uiState.first()
        assertTrue("SelfLock should be active during 23:30", state.selfLockStatus.isSelfLocked)
        assertEquals(listOf("com.dv.adm"), state.selfLockStatus.activeLockedPackages)
    }

    @Test
    fun selfLockInactive_whenOutsideScheduledWindow() = runTest(testDispatcher) {
        // Target: hide at 23:00 (1380), reveal at 07:00 (420)
        val config = TargetAppConfig("com.dv.adm", 60, 1380, 420)
        settingsRepository.putTarget(config)

        // Set time to 12:00 (outside window)
        timeSource.setNowMillis(zdtToMillis(12, 0))
        viewModel.tickCurrentTime()

        val state = viewModel.uiState.first()
        assertFalse("SelfLock should be inactive at 12:00", state.selfLockStatus.isSelfLocked)
    }

    @Test
    fun dailyUsageLimitLockAlone_doesNotTriggerSelfLock() = runTest(testDispatcher) {
        // Target: hide at 23:00 (1380), reveal at 07:00 (420)
        val config = TargetAppConfig("com.dv.adm", 60, 1380, 420)
        settingsRepository.putTarget(config)

        // Set time to 14:00 (outside window)
        timeSource.setNowMillis(zdtToMillis(14, 0))
        viewModel.tickCurrentTime()

        // App exceeded limit and is locked until midnight
        lockStateStore.ensureRecord("com.dv.adm")
        lockStateStore.lockUntilAtLeast("com.dv.adm", zdtToMillis(23, 59))
        usageSnapshotStore.publish(UsageSnapshot.Available("com.dv.adm", 65 * 60 * 1000L, 0L, 60))

        val state = viewModel.uiState.first()
        assertFalse("Daily usage limit lock alone must NOT trigger selfLock", state.selfLockStatus.isSelfLocked)
        assertEquals(LockState.LockedUntil(zdtToMillis(23, 59)), state.lockStates["com.dv.adm"]?.state)
    }

    @Test
    fun mutationsRejected_whenSelfLocked() = runTest(testDispatcher) {
        val config = TargetAppConfig("com.dv.adm", 60, 1380, 420)
        settingsRepository.putTarget(config)

        // Move into lock window (23:30)
        timeSource.setNowMillis(zdtToMillis(23, 30))
        viewModel.tickCurrentTime()

        val state = viewModel.uiState.first()
        assertTrue(state.selfLockStatus.isSelfLocked)

        // Attempt to edit config
        viewModel.submitConfig(TargetAppConfig("com.dv.adm", 120, 1380, 420))
        // Target should remain unchanged
        val updatedConfig = settingsRepository.targets.first()["com.dv.adm"]
        assertEquals(60, updatedConfig?.dailyLimitMinutes)

        // Attempt to remove config
        viewModel.removeConfig("com.dv.adm")
        val afterRemove = settingsRepository.targets.first()["com.dv.adm"]
        assertEquals(config, afterRemove)
    }

    @Test
    fun refreshSystemStatus_updatesUiState() = runTest(testDispatcher) {
        statusChecker.status = SystemStatus(
            isDeviceOwner = true,
            hasUsageAccess = false,
            hasNotificationPermission = true,
            hasExactAlarmPermission = false,
            isUninstallBlocked = true,
            isUserControlDisabled = true
        )
        viewModel.refreshSystemStatus()

        val state = viewModel.uiState.first()
        assertTrue(state.isDeviceOwner)
        assertFalse(state.hasUsageAccess)
        assertFalse(state.hasExactAlarmPermission)
    }
}
