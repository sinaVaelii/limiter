package com.limiter.app.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.limiter.app.data.AppDiscoveryRepository
import com.limiter.app.data.CriticalPackageResolver
import com.limiter.app.data.DiscoveredApp
import com.limiter.app.data.InMemoryLockStateStore
import com.limiter.app.data.SettingsRepository
import com.limiter.app.data.UsageSnapshotStore
import com.limiter.app.dpm.AppFreezer
import com.limiter.app.dpm.FakeDpmGateway
import com.limiter.app.model.TargetAppConfig
import com.limiter.app.policy.LockCoordinator
import com.limiter.app.time.FakeTimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class TargetScheduleEditTest {

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

    private fun zdtToMillis(hour: Int, minute: Int): Long {
        return ZonedDateTime.of(2026, 10, 11, hour, minute, 0, 0, utc).toInstant().toEpochMilli()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
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

        // Midday (12:00 UTC) outside default 23:00..07:00 window
        timeSource = FakeTimeSource()
        timeSource.setNowMillis(zdtToMillis(12, 0))
        timeSource.setZone(utc)

        resolver = FakeCriticalPackageResolver()
        discoveryRepo = FakeAppDiscoveryRepository(resolver = resolver)

        coordinator = LockCoordinator(
            gateway = gateway,
            freezer = freezer,
            settingsRepository = settingsRepository,
            lockStateStore = lockStateStore,
            timeSource = timeSource,
            criticalPackageResolver = resolver
        )

        viewModel = MainViewModel(
            dpmGateway = gateway,
            settingsRepository = settingsRepository,
            lockStateStore = lockStateStore,
            usageSnapshotStore = usageSnapshotStore,
            timeSource = timeSource,
            lockCoordinator = coordinator,
            appDiscoveryRepository = discoveryRepo,
            criticalPackageResolver = resolver,
            systemStatusChecker = null
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // 1. An existing target's hide time can be changed
    @Test
    fun `existing target hide time can be changed`() = runTest(testDispatcher) {
        val pkg = "com.dv.adm"
        gateway.installedPackages.add(pkg)
        val initial = TargetAppConfig(pkg, dailyLimitMinutes = 60, hideAtMinuteOfDay = 1380, revealAtMinuteOfDay = 420) // 23:00 to 07:00
        settingsRepository.putTarget(initial)

        // Change Hide to 22:30 (1350)
        val updated = initial.copy(hideAtMinuteOfDay = 1350)
        viewModel.submitConfig(updated)

        // Current target retains active config for today
        val activeTarget = settingsRepository.snapshotTargets()[pkg]
        assertNotNull(activeTarget)
        assertEquals(1380, activeTarget!!.hideAtMinuteOfDay)

        // Pending change holds desired 22:30
        val pending = settingsRepository.snapshotPending().find { it.packageName == pkg }
        assertNotNull(pending)
        assertNotNull(pending!!.desired)
        assertEquals(1350, pending.desired!!.hideAtMinuteOfDay)
        assertEquals(420, pending.desired!!.revealAtMinuteOfDay)
    }

    // 2. An existing target's reveal time can be changed
    @Test
    fun `existing target reveal time can be changed`() = runTest(testDispatcher) {
        val pkg = "com.dv.adm"
        gateway.installedPackages.add(pkg)
        val initial = TargetAppConfig(pkg, dailyLimitMinutes = 60, hideAtMinuteOfDay = 1380, revealAtMinuteOfDay = 420) // 23:00 to 07:00
        settingsRepository.putTarget(initial)

        // Change Reveal to 06:30 (390)
        val updated = initial.copy(revealAtMinuteOfDay = 390)
        viewModel.submitConfig(updated)

        val pending = settingsRepository.snapshotPending().find { it.packageName == pkg }
        assertNotNull(pending)
        assertNotNull(pending!!.desired)
        assertEquals(1380, pending.desired!!.hideAtMinuteOfDay)
        assertEquals(390, pending.desired!!.revealAtMinuteOfDay)
    }

    // 3. Both times can be changed in a single edit
    @Test
    fun `both times can be changed in a single edit`() = runTest(testDispatcher) {
        val pkg = "com.dv.adm"
        gateway.installedPackages.add(pkg)
        val initial = TargetAppConfig(pkg, dailyLimitMinutes = 60, hideAtMinuteOfDay = 1380, revealAtMinuteOfDay = 420)
        settingsRepository.putTarget(initial)

        // Change Hide to 22:30 (1350) and Reveal to 06:30 (390)
        val updated = initial.copy(hideAtMinuteOfDay = 1350, revealAtMinuteOfDay = 390)
        viewModel.submitConfig(updated)

        val pending = settingsRepository.snapshotPending().find { it.packageName == pkg }
        assertNotNull(pending)
        assertNotNull(pending!!.desired)
        assertEquals(1350, pending.desired!!.hideAtMinuteOfDay)
        assertEquals(390, pending.desired!!.revealAtMinuteOfDay)
    }

    // 4. Changing times again updates pending desired configuration rather than resurrecting initial configuration
    @Test
    fun `changing times again updates pending desired configuration repeatedly`() = runTest(testDispatcher) {
        val pkg = "com.dv.adm"
        gateway.installedPackages.add(pkg)
        val initial = TargetAppConfig(pkg, dailyLimitMinutes = 60, hideAtMinuteOfDay = 1380, revealAtMinuteOfDay = 420)
        settingsRepository.putTarget(initial)

        // First edit: Hide 22:30 (1350), Reveal 06:30 (390)
        val firstEdit = initial.copy(hideAtMinuteOfDay = 1350, revealAtMinuteOfDay = 390)
        viewModel.submitConfig(firstEdit)

        var pending = settingsRepository.snapshotPending().find { it.packageName == pkg }
        assertEquals(1350, pending?.desired?.hideAtMinuteOfDay)
        assertEquals(390, pending?.desired?.revealAtMinuteOfDay)

        // Second edit: User changes again to Hide 22:00 (1320), Reveal 06:00 (360)
        val secondEdit = firstEdit.copy(hideAtMinuteOfDay = 1320, revealAtMinuteOfDay = 360)
        viewModel.submitConfig(secondEdit)

        // Verified: The pending change is updated with the latest desired configuration,
        // NOT resurrected to the initial (1380, 420) nor stuck permanently on the first edit (1350, 390)
        pending = settingsRepository.snapshotPending().find { it.packageName == pkg }
        assertNotNull(pending)
        assertEquals(1320, pending!!.desired!!.hideAtMinuteOfDay)
        assertEquals(360, pending.desired!!.revealAtMinuteOfDay)

        val allPending = settingsRepository.snapshotPending().filter { it.packageName == pkg }
        assertEquals("Only one pending change per package allowed", 1, allPending.size)
    }

    // 5. Reopening editor reflects saved desired schedule (editor target resolution logic)
    @Test
    fun `editor target resolution prefers pending desired config over stale active config`() = runTest(testDispatcher) {
        val pkg = "com.dv.adm"
        gateway.installedPackages.add(pkg)
        val initial = TargetAppConfig(pkg, dailyLimitMinutes = 60, hideAtMinuteOfDay = 1380, revealAtMinuteOfDay = 420)
        settingsRepository.putTarget(initial)

        // Edit to 22:30 and 06:30
        val updated = TargetAppConfig(pkg, dailyLimitMinutes = 60, hideAtMinuteOfDay = 1350, revealAtMinuteOfDay = 390)
        viewModel.submitConfig(updated)

        val state = viewModel.uiState.value
        val config = state.targets[pkg]
        val pending = state.pendingChanges.find { it.packageName == pkg }

        // The resolution used when opening the editor in LimiterScreen
        val editingTarget = pending?.desired ?: config ?: TargetAppConfig(pkg, 60, 1380, 420)

        assertEquals("Editor must open with latest saved desired hide time", 1350, editingTarget.hideAtMinuteOfDay)
        assertEquals("Editor must open with latest saved desired reveal time", 390, editingTarget.revealAtMinuteOfDay)
    }

    // 6. Editor initializes fields correctly from active config when no pending change exists
    @Test
    fun `editor target resolution uses active config when no pending change exists`() = runTest(testDispatcher) {
        val pkg = "com.dv.adm"
        gateway.installedPackages.add(pkg)
        val initial = TargetAppConfig(pkg, dailyLimitMinutes = 45, hideAtMinuteOfDay = 1300, revealAtMinuteOfDay = 400)
        settingsRepository.putTarget(initial)

        val state = viewModel.uiState.value
        val config = state.targets[pkg]
        val pending = state.pendingChanges.find { it.packageName == pkg }

        val editingTarget = pending?.desired ?: config ?: TargetAppConfig(pkg, 60, 1380, 420)

        assertEquals(45, editingTarget.dailyLimitMinutes)
        assertEquals(1300, editingTarget.hideAtMinuteOfDay)
        assertEquals(400, editingTarget.revealAtMinuteOfDay)
    }

    // 7. Validation prevents identical hide and reveal times
    @Test
    fun `validation rejects identical hide and reveal times`() {
        var threwException = false
        try {
            TargetAppConfig("com.dv.adm", 60, 720, 720)
        } catch (_: IllegalArgumentException) {
            threwException = true
        }
        assertTrue("TargetAppConfig init must reject identical hide and reveal times", threwException)
    }

    // 8. Reconcile applies the pending window change once eligible
    @Test
    fun `reconcile applies pending window change once epoch-day becomes eligible`() = runTest(testDispatcher) {
        val pkg = "com.dv.adm"
        gateway.installedPackages.add(pkg)
        val initial = TargetAppConfig(pkg, dailyLimitMinutes = 60, hideAtMinuteOfDay = 1380, revealAtMinuteOfDay = 420)
        settingsRepository.putTarget(initial)

        // Queue window change to 22:30 (1350) and 06:30 (390)
        val updated = TargetAppConfig(pkg, dailyLimitMinutes = 60, hideAtMinuteOfDay = 1350, revealAtMinuteOfDay = 390)
        viewModel.submitConfig(updated)

        // Day 1: pending not eligible yet
        coordinator.reconcile()
        assertEquals(1380, settingsRepository.snapshotTargets()[pkg]?.hideAtMinuteOfDay)
        assertEquals(1, settingsRepository.snapshotPending().size)

        // Advance time to tomorrow (+24 hours), target is Unlocked
        timeSource.setNowMillis(timeSource.nowMillis() + 86_400_000L)

        // Reconcile on day 2
        coordinator.reconcile()

        // Verified: The pending window change is promoted into active targets and cleared from pending
        val effective = settingsRepository.snapshotTargets()[pkg]
        assertNotNull(effective)
        assertEquals(1350, effective!!.hideAtMinuteOfDay)
        assertEquals(390, effective.revealAtMinuteOfDay)
        assertTrue("Pending queue must be cleared after application", settingsRepository.snapshotPending().isEmpty())
    }

    // 9. Reverting edit back to active configuration clears pending change
    @Test
    fun `reverting edit back to active configuration cancels pending change`() = runTest(testDispatcher) {
        val pkg = "com.dv.adm"
        gateway.installedPackages.add(pkg)
        val initial = TargetAppConfig(pkg, dailyLimitMinutes = 60, hideAtMinuteOfDay = 1380, revealAtMinuteOfDay = 420)
        settingsRepository.putTarget(initial)

        // Step 1: Change to 22:30 (1350) and 06:30 (390)
        viewModel.submitConfig(initial.copy(hideAtMinuteOfDay = 1350, revealAtMinuteOfDay = 390))
        assertEquals(1, settingsRepository.snapshotPending().size)

        // Step 2: User changes their mind and re-applies initial values (1380, 420)
        viewModel.submitConfig(initial)

        // Verified: Pending change is removed because the configuration matches active target
        assertTrue("Pending change must be cleared when reverted to active config", settingsRepository.snapshotPending().isEmpty())
        assertEquals(1380, settingsRepository.snapshotTargets()[pkg]?.hideAtMinuteOfDay)
    }
}
