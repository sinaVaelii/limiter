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
import com.limiter.app.model.LockRecord
import com.limiter.app.model.LockState
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class AppPickerAndConfigTest {

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
        timeSource.setNowMillis(zdtToMillis(12, 0))
        timeSource.setZone(utc)

        resolver = FakeCriticalPackageResolver()
        resolver.protectedPackages.addAll(listOf("com.limiter.app", "com.android.systemui", "com.android.settings"))

        discoveryRepo = FakeAppDiscoveryRepository(
            allInstalledApps = mutableListOf(
                DiscoveredApp("com.dv.adm", "Advanced Download Manager"),
                DiscoveredApp("org.telegram.messenger", "Telegram"),
                DiscoveredApp("ir.divar", "Divar"),
                DiscoveredApp("com.google.android.youtube", "YouTube"),
                DiscoveredApp("com.android.systemui", "SystemUI"), // Protected
                DiscoveredApp("com.limiter.app", "Limiter") // Self/Protected
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

        Dispatchers.setMain(testDispatcher)

        viewModel = MainViewModel(
            dpmGateway = gateway,
            settingsRepository = settingsRepository,
            lockStateStore = lockStateStore,
            usageSnapshotStore = usageSnapshotStore,
            timeSource = timeSource,
            lockCoordinator = coordinator,
            appDiscoveryRepository = discoveryRepo,
            criticalPackageResolver = resolver
        )
    }

    @org.junit.After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    // ==========================================
    // 1. App Picker Eligibility & Filtering Tests
    // ==========================================

    @Test
    fun `picker filters out protected packages and Limiter itself`() = runTest(testDispatcher) {
        viewModel.loadAvailableApps()
        val apps = viewModel.uiState.value.availableApps

        val pkgs = apps.map { it.packageName }
        assertFalse("Limiter itself must be excluded", pkgs.contains("com.limiter.app"))
        assertFalse("SystemUI must be excluded", pkgs.contains("com.android.systemui"))
        assertTrue("User apps must be included", pkgs.contains("com.dv.adm"))
        assertTrue("User apps must be included", pkgs.contains("org.telegram.messenger"))
    }

    @Test
    fun `picker filters out already configured targets`() = runTest(testDispatcher) {
        // Configure Divar as a target
        settingsRepository.putTarget(TargetAppConfig("ir.divar", 60, 1380, 420))

        viewModel.loadAvailableApps()
        val apps = viewModel.uiState.value.availableApps
        val pkgs = apps.map { it.packageName }

        assertFalse("Already configured target must be excluded from picker", pkgs.contains("ir.divar"))
        assertTrue("Unconfigured target must remain available", pkgs.contains("com.dv.adm"))
    }

    @Test
    fun `picker filters out system ineligible packages`() = runTest(testDispatcher) {
        resolver.ineligiblePackages.add("com.google.android.youtube")

        viewModel.loadAvailableApps()
        val apps = viewModel.uiState.value.availableApps
        val pkgs = apps.map { it.packageName }

        assertFalse("Ineligible package must be excluded", pkgs.contains("com.google.android.youtube"))
        assertTrue("Eligible package must be included", pkgs.contains("org.telegram.messenger"))
    }

    // ==========================================
    // 2. Search & Selection Stability Tests
    // ==========================================

    @Test
    fun `search query filters apps by label case-insensitively`() = runTest(testDispatcher) {
        viewModel.loadAvailableApps()
        viewModel.setSearchQuery("tele")

        val state = viewModel.uiState.value
        assertEquals("tele", state.searchQuery)

        val filtered = state.availableApps.filter {
            it.label.contains(state.searchQuery, ignoreCase = true)
        }
        assertEquals(1, filtered.size)
        assertEquals("org.telegram.messenger", filtered[0].packageName)
    }

    @Test
    fun `changing search query preserves prior selections`() = runTest(testDispatcher) {
        viewModel.loadAvailableApps()

        // Select ADM
        viewModel.togglePackageSelection("com.dv.adm")
        assertTrue(viewModel.uiState.value.selectedPackages.contains("com.dv.adm"))

        // Search for Telegram
        viewModel.setSearchQuery("tele")
        // Selection must NOT be cleared by searching
        assertTrue("Prior selection must persist across query changes", viewModel.uiState.value.selectedPackages.contains("com.dv.adm"))

        // Select Telegram while filtered
        viewModel.togglePackageSelection("org.telegram.messenger")
        assertEquals(2, viewModel.uiState.value.selectedPackages.size)
        assertTrue(viewModel.uiState.value.selectedPackages.contains("com.dv.adm"))
        assertTrue(viewModel.uiState.value.selectedPackages.contains("org.telegram.messenger"))

        // Clear query
        viewModel.setSearchQuery("")
        // Both selections must still be present
        assertEquals(2, viewModel.uiState.value.selectedPackages.size)
    }

    @Test
    fun `toggle selection deselects when clicked again`() = runTest(testDispatcher) {
        viewModel.togglePackageSelection("com.dv.adm")
        assertTrue(viewModel.uiState.value.selectedPackages.contains("com.dv.adm"))

        viewModel.togglePackageSelection("com.dv.adm")
        assertFalse(viewModel.uiState.value.selectedPackages.contains("com.dv.adm"))
        assertTrue(viewModel.uiState.value.selectedPackages.isEmpty())
    }

    @Test
    fun `select all adds all filtered apps while deselect clears`() = runTest(testDispatcher) {
        viewModel.loadAvailableApps()
        val apps = viewModel.uiState.value.availableApps

        viewModel.selectAllEligible(apps)
        assertEquals(apps.size, viewModel.uiState.value.selectedPackages.size)

        viewModel.clearSelections()
        assertTrue(viewModel.uiState.value.selectedPackages.isEmpty())
    }

    // ==========================================
    // 3. Batch Configuration & Multi-selection Submission Tests
    // ==========================================

    @Test
    fun `batch configuration submits shared settings to multiple apps sequentially`() = runTest(testDispatcher) {
        val targets = setOf("com.dv.adm", "org.telegram.messenger")

        viewModel.submitBatchConfig(
            packageNames = targets,
            dailyLimitMinutes = 60,
            hideAtMinuteOfDay = 1380, // 23:00
            revealAtMinuteOfDay = 420  // 07:00
        )

        val batchRes = viewModel.uiState.value.batchResult
        assertNotNull(batchRes)
        assertEquals(2, batchRes!!.succeededPackages.size)
        assertTrue(batchRes.succeededPackages.contains("com.dv.adm"))
        assertTrue(batchRes.succeededPackages.contains("org.telegram.messenger"))
        assertTrue(batchRes.failedPackages.isEmpty())

        // Confirmed saved in repository
        val saved = settingsRepository.snapshotTargets()
        assertEquals(60, saved["com.dv.adm"]?.dailyLimitMinutes)
        assertEquals(1380, saved["com.dv.adm"]?.hideAtMinuteOfDay)
        assertEquals(420, saved["com.dv.adm"]?.revealAtMinuteOfDay)

        assertEquals(60, saved["org.telegram.messenger"]?.dailyLimitMinutes)
        assertEquals(1380, saved["org.telegram.messenger"]?.hideAtMinuteOfDay)
        assertEquals(420, saved["org.telegram.messenger"]?.revealAtMinuteOfDay)
    }

    @Test
    fun `batch configuration reports per-package failures when some are protected`() = runTest(testDispatcher) {
        val targets = setOf("com.dv.adm", "com.android.systemui")

        viewModel.submitBatchConfig(
            packageNames = targets,
            dailyLimitMinutes = 60,
            hideAtMinuteOfDay = 1380,
            revealAtMinuteOfDay = 420
        )

        val batchRes = viewModel.uiState.value.batchResult
        assertNotNull(batchRes)
        assertEquals(1, batchRes!!.succeededPackages.size)
        assertTrue(batchRes.succeededPackages.contains("com.dv.adm"))
        assertEquals(1, batchRes.failedPackages.size)
        assertTrue(batchRes.failedPackages.containsKey("com.android.systemui"))
    }

    // ==========================================
    // 4. Time & Duration Validation Tests
    // ==========================================

    @Test
    fun `rejects invalid daily limits`() = runTest(testDispatcher) {
        // 0 minutes is invalid
        viewModel.submitBatchConfig(setOf("com.dv.adm"), 0, 1380, 420)
        assertNotNull(viewModel.uiState.value.feedbackMessage)
        assertTrue(settingsRepository.snapshotTargets().isEmpty())

        // Negative minutes is invalid
        viewModel.submitBatchConfig(setOf("com.dv.adm"), -10, 1380, 420)
        assertTrue(settingsRepository.snapshotTargets().isEmpty())

        // > 720 minutes is invalid
        viewModel.submitBatchConfig(setOf("com.dv.adm"), 721, 1380, 420)
        assertTrue(settingsRepository.snapshotTargets().isEmpty())
    }

    @Test
    fun `rejects equal hide and reveal times`() = runTest(testDispatcher) {
        viewModel.submitBatchConfig(setOf("com.dv.adm"), 60, 420, 420)
        assertNotNull(viewModel.uiState.value.feedbackMessage)
        assertTrue(settingsRepository.snapshotTargets().isEmpty())
    }

    @Test
    fun `accepts overnight window where hide greater than reveal`() = runTest(testDispatcher) {
        viewModel.submitBatchConfig(setOf("com.dv.adm"), 60, 1380, 420) // 23:00 to 07:00
        val saved = settingsRepository.snapshotTargets()["com.dv.adm"]
        assertNotNull(saved)
        assertEquals(1380, saved!!.hideAtMinuteOfDay)
        assertEquals(420, saved.revealAtMinuteOfDay)
    }

    @Test
    fun `accepts daytime window where hide less than reveal`() = runTest(testDispatcher) {
        viewModel.submitBatchConfig(setOf("com.dv.adm"), 60, 540, 1020) // 09:00 to 17:00
        val saved = settingsRepository.snapshotTargets()["com.dv.adm"]
        assertNotNull(saved)
        assertEquals(540, saved!!.hideAtMinuteOfDay)
        assertEquals(1020, saved.revealAtMinuteOfDay)
    }

    // ==========================================
    // 5. Existing Targets Editing & Removal (Req 2)
    // ==========================================

    @Test
    fun `existing targets remain editable and apply tightening immediately`() = runTest(testDispatcher) {
        // Initial config
        val initial = TargetAppConfig("com.dv.adm", 60, 1380, 420)
        settingsRepository.putTarget(initial)

        // Tighten limit from 60 to 30 min
        val updated = initial.copy(dailyLimitMinutes = 30)
        viewModel.submitConfig(updated)

        val after = settingsRepository.snapshotTargets()["com.dv.adm"]
        assertEquals(30, after?.dailyLimitMinutes)
    }

    @Test
    fun `existing targets loosening is safely deferred to next day`() = runTest(testDispatcher) {
        val initial = TargetAppConfig("com.dv.adm", 60, 1380, 420)
        settingsRepository.putTarget(initial)

        // Loosen limit from 60 to 90 min (+30 min is maximum allowed increase)
        val updated = initial.copy(dailyLimitMinutes = 90)
        viewModel.submitConfig(updated)

        // Target remains 60 min for today
        assertEquals(60, settingsRepository.snapshotTargets()["com.dv.adm"]?.dailyLimitMinutes)
        // Pending change is queued
        val pending = settingsRepository.snapshotPending()
        assertEquals(1, pending.size)
        assertEquals("com.dv.adm", pending[0].packageName)
        assertEquals(90, pending[0].desired?.dailyLimitMinutes)
    }

    @Test
    fun `target removal is deferred and repopulates picker after removal`() = runTest(testDispatcher) {
        val initial = TargetAppConfig("com.dv.adm", 60, 1380, 420)
        settingsRepository.putTarget(initial)

        viewModel.removeConfig("com.dv.adm")

        // Removal while active is deferred per spec
        val pending = settingsRepository.snapshotPending()
        assertEquals(1, pending.size)
        assertEquals("com.dv.adm", pending[0].packageName)
        assertNull(pending[0].desired)
    }

    // ==========================================
    // 6. Preservation of Hotfix & Active Locks (Req 5)
    // ==========================================

    @Test
    fun `editing configuration never unlocks active lock or shortens deadline`() = runTest(testDispatcher) {
        val deadline = zdtToMillis(20, 0)
        lockStateStore.lockUntilAtLeast("ir.divar", deadline)
        settingsRepository.putTarget(TargetAppConfig("ir.divar", 60, 1380, 420))

        // Edit config on divar
        viewModel.submitConfig(TargetAppConfig("ir.divar", 30, 1380, 420))

        // Lock state must remain intact
        val record = lockStateStore.snapshot()["ir.divar"]
        assertNotNull(record)
        val lockedState = record!!.state as LockState.LockedUntil
        assertEquals(deadline, lockedState.untilEpochMillis)
    }
}
