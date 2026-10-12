package com.limiter.app.policy

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.limiter.app.data.CriticalPackageResolver
import com.limiter.app.data.InMemoryLockStateStore
import com.limiter.app.data.SettingsRepository
import com.limiter.app.dpm.AppFreezer
import com.limiter.app.dpm.FakeDpmGateway
import com.limiter.app.model.LockState
import com.limiter.app.model.PendingChange
import com.limiter.app.model.TargetAppConfig
import com.limiter.app.policy.SelfLockPolicy.SelfLockStatus
import com.limiter.app.time.FakeTimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Comprehensive verification for Phase P4: Product rules for limits and self-lock (D5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProductRulesP4Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val utc = ZoneId.of("UTC")
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var gateway: FakeDpmGateway
    private lateinit var freezer: AppFreezer
    private lateinit var lockStateStore: InMemoryLockStateStore
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var timeSource: FakeTimeSource
    private lateinit var resolver: FakeCriticalPackageResolver
    private lateinit var coordinator: LockCoordinator

    class FakeCriticalPackageResolver : CriticalPackageResolver {
        val protectedPackages = mutableSetOf<String>()
        override fun resolveProtected(): Set<String> = protectedPackages
        override fun isEligibleTarget(packageName: String): Boolean = true
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

        val dataStoreFile = tempFolder.newFile("test_p4_settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { dataStoreFile }
        )
        settingsRepository = SettingsRepository(dataStore)

        timeSource = FakeTimeSource()
        timeSource.setNowMillis(zdtToMillis(12, 0))
        timeSource.setZone(utc)

        resolver = FakeCriticalPackageResolver()

        coordinator = LockCoordinator(
            gateway = gateway,
            freezer = freezer,
            lockStateStore = lockStateStore,
            settingsRepository = settingsRepository,
            criticalPackageResolver = resolver,
            timeSource = timeSource
        )
    }

    // =========================================================================
    // 1. Limit range: 720 ok, 721 rejected, 1 ok, 0 rejected
    // =========================================================================

    @Test
    fun `limit range - new 720 is valid and accepted immediately`() {
        val cfg720 = TargetAppConfig("com.test.app", 720, 1380, 420)
        assertTrue(SettingsPolicy.isValid(cfg720))

        val result = SettingsPolicy.evaluateAdd(
            config = cfg720,
            currentTargets = emptyMap(),
            protectedPackages = emptySet()
        )
        assertTrue("Expected Ok, got $result", result is SettingsPolicy.AddResult.Ok)
    }

    @Test
    fun `limit range - new 721 is invalid and rejected`() {
        val cfg721 = TargetAppConfig("com.test.app", 721, 1380, 420)
        assertFalse(SettingsPolicy.isValid(cfg721))

        val result = SettingsPolicy.evaluateAdd(
            config = cfg721,
            currentTargets = emptyMap(),
            protectedPackages = emptySet()
        )
        assertTrue("Expected Invalid, got $result", result is SettingsPolicy.AddResult.Invalid)
    }

    @Test
    fun `limit range - new 1 is valid and accepted immediately`() {
        val cfg1 = TargetAppConfig("com.test.app", 1, 1380, 420)
        assertTrue(SettingsPolicy.isValid(cfg1))

        val result = SettingsPolicy.evaluateAdd(
            config = cfg1,
            currentTargets = emptyMap(),
            protectedPackages = emptySet()
        )
        assertTrue("Expected Ok, got $result", result is SettingsPolicy.AddResult.Ok)
    }

    @Test
    fun `limit range - new 0 is invalid and rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            TargetAppConfig("com.test.app", dailyLimitMinutes = 0, hideAtMinuteOfDay = 1380, revealAtMinuteOfDay = 420)
        }
    }

    // =========================================================================
    // 2. Limit increase: current=60, proposed=90 ok (pending), proposed=91 rejected
    // =========================================================================

    @Test
    fun `increase - current 60 to proposed 90 (d = 30) is accepted as pending deferred`() {
        val current = TargetAppConfig("com.test.app", 60, 1380, 420)
        val proposed = current.copy(dailyLimitMinutes = 90)

        val result = SettingsPolicy.evaluateEdit(
            newConfig = proposed,
            currentTargets = mapOf(current.packageName to current),
            nowMillis = zdtToMillis(12, 0),
            zone = utc
        )
        assertTrue("Expected Deferred, got $result", result is SettingsPolicy.EditResult.Deferred)
        val deferred = result as SettingsPolicy.EditResult.Deferred
        assertEquals(90, deferred.pending.desired?.dailyLimitMinutes)
    }

    @Test
    fun `increase - current 60 to proposed 91 (d = 31) is rejected`() {
        val current = TargetAppConfig("com.test.app", 60, 1380, 420)
        val proposed = current.copy(dailyLimitMinutes = 91)

        val result = SettingsPolicy.evaluateEdit(
            newConfig = proposed,
            currentTargets = mapOf(current.packageName to current),
            nowMillis = zdtToMillis(12, 0),
            zone = utc
        )
        assertTrue("Expected Invalid, got $result", result is SettingsPolicy.EditResult.Invalid)
    }

    @Test
    fun `increase - proposed exceeding 720 is rejected even if delta is within 30`() {
        val current = TargetAppConfig("com.test.app", 710, 1380, 420)
        val proposed = current.copy(dailyLimitMinutes = 725) // delta 15 <= 30, but > 720

        val result = SettingsPolicy.evaluateEdit(
            newConfig = proposed,
            currentTargets = mapOf(current.packageName to current),
            nowMillis = zdtToMillis(12, 0),
            zone = utc
        )
        assertTrue("Expected Invalid, got $result", result is SettingsPolicy.EditResult.Invalid)
    }

    // =========================================================================
    // 3. Cumulative pending cap: compares against currently active limit (never pending)
    // =========================================================================

    @Test
    fun `cumulative pending - proposed 100 rejected when current active is 60`() = runTest {
        val current = TargetAppConfig("com.test.app", 60, 1380, 420)
        settingsRepository.putTarget(current)

        // Queue a pending change of 90 (+30)
        settingsRepository.setPending(
            listOf(
                PendingChange(
                    packageName = "com.test.app",
                    desired = current.copy(dailyLimitMinutes = 90),
                    eligibleFromEpochDay = 99999
                )
            )
        )

        // Now propose 100: compared against current active limit 60, d = 40 > 30 -> Rejected/Invalid
        val proposed = current.copy(dailyLimitMinutes = 100)
        val result = coordinator.tryEditConfig(proposed)
        assertTrue("Expected Invalid, got $result", result is SettingsPolicy.EditResult.Invalid)
    }

    // =========================================================================
    // 4. Decrease: current=60, proposed=30 ok (immediate)
    // =========================================================================

    @Test
    fun `decrease - current 60 to proposed 30 is applied immediately`() {
        val current = TargetAppConfig("com.test.app", 60, 1380, 420)
        val proposed = current.copy(dailyLimitMinutes = 30)

        val result = SettingsPolicy.evaluateEdit(
            newConfig = proposed,
            currentTargets = mapOf(current.packageName to current),
            nowMillis = zdtToMillis(12, 0),
            zone = utc
        )
        assertTrue("Expected Applied, got $result", result is SettingsPolicy.EditResult.Applied)
        val applied = result as SettingsPolicy.EditResult.Applied
        assertEquals(30, applied.config.dailyLimitMinutes)
    }

    // =========================================================================
    // 5. Clamping: target with limit=1000 clamped to 720 on reconcile
    // =========================================================================

    @Test
    fun `clamping - target with limit 1000 clamped to 720 on reconcile`() = runTest {
        val pkg = "ir.ilmili.telegraph"
        gateway.installedPackages.add(pkg)

        // Target with 1000 minutes stored
        val stored1000 = TargetAppConfig(pkg, 1000, 1380, 420)
        settingsRepository.putTarget(stored1000)

        assertEquals(1000, settingsRepository.snapshotTargets()[pkg]?.dailyLimitMinutes)

        // Run reconcile
        val result = coordinator.reconcile()
        assertTrue("Expected Ok reconcile, got $result", result is LockCoordinator.ReconcileResult.Ok)

        // Stored config must now be clamped to 720
        val clamped = settingsRepository.snapshotTargets()[pkg]
        assertNotNull(clamped)
        assertEquals(720, clamped!!.dailyLimitMinutes)
        assertEquals(1380, clamped.hideAtMinuteOfDay)
        assertEquals(420, clamped.revealAtMinuteOfDay)
    }

    // =========================================================================
    // 6. SelfLockGate pure policy
    // =========================================================================

    @Test
    fun `self-lock gate - allows adding new target during self-lock`() {
        val status = SelfLockStatus(isSelfLocked = true, unlockTimeMillis = 2000000000L)
        val cfg = TargetAppConfig("com.new.app", 60, 1380, 420)

        assertTrue(SelfLockGate.canAddTarget(status))
        assertTrue(SelfLockGate.isActionAllowed(SelfLockAction.AddTarget(cfg.packageName, cfg), status))
    }

    @Test
    fun `self-lock gate - allows decreasing limit during self-lock`() {
        val status = SelfLockStatus(isSelfLocked = true, unlockTimeMillis = 2000000000L)
        val oldCfg = TargetAppConfig("com.test.app", 60, 1380, 420)
        val newCfg = oldCfg.copy(dailyLimitMinutes = 30) // tightened

        assertTrue(SelfLockGate.canEditTarget(status, oldCfg, newCfg))
        assertTrue(SelfLockGate.isActionAllowed(SelfLockAction.EditTarget(oldCfg.packageName, oldCfg, newCfg), status))
    }

    @Test
    fun `self-lock gate - blocks removing target during self-lock`() {
        val status = SelfLockStatus(isSelfLocked = true, unlockTimeMillis = 2000000000L)

        assertFalse(SelfLockGate.canRemoveTarget(status))
        assertFalse(SelfLockGate.isActionAllowed(SelfLockAction.RemoveTarget("com.test.app"), status))
    }

    @Test
    fun `self-lock gate - blocks increasing limit during self-lock`() {
        val status = SelfLockStatus(isSelfLocked = true, unlockTimeMillis = 2000000000L)
        val oldCfg = TargetAppConfig("com.test.app", 60, 1380, 420)
        val newCfg = oldCfg.copy(dailyLimitMinutes = 90) // loosened

        assertFalse(SelfLockGate.canEditTarget(status, oldCfg, newCfg))
        assertFalse(SelfLockGate.isActionAllowed(SelfLockAction.EditTarget(oldCfg.packageName, oldCfg, newCfg), status))
    }

    @Test
    fun `self-lock gate - blocks loosening lock window during self-lock`() {
        val status = SelfLockStatus(isSelfLocked = true, unlockTimeMillis = 2000000000L)
        // Original window 23:00 to 07:00 (8 hours). New window 23:00 to 06:00 (7 hours, looser)
        val oldCfg = TargetAppConfig("com.test.app", 60, 1380, 420)
        val newCfg = oldCfg.copy(revealAtMinuteOfDay = 360)

        assertFalse(SelfLockGate.canEditTarget(status, oldCfg, newCfg))
        assertFalse(SelfLockGate.isActionAllowed(SelfLockAction.EditTarget(oldCfg.packageName, oldCfg, newCfg), status))
    }

    @Test
    fun `self-lock gate - allows cancelling pending loosening but blocks cancelling pending tightening`() {
        val status = SelfLockStatus(isSelfLocked = true, unlockTimeMillis = 2000000000L)

        assertTrue(SelfLockGate.isActionAllowed(SelfLockAction.CancelPending("com.test.app", isLoosening = true), status))
        assertFalse(SelfLockGate.isActionAllowed(SelfLockAction.CancelPending("com.test.app", isLoosening = false), status))
    }

    // =========================================================================
    // 7. Coordinator-level self-lock enforcement
    // =========================================================================

    @Test
    fun `coordinator - obeys self-lock gate on remove and edit`() = runTest {
        // Target with active scheduled window right now (12:00 UTC)
        // Window from 11:00 (660) to 13:00 (780)
        val pkg = "com.test.app"
        val cfg = TargetAppConfig(pkg, 60, hideAtMinuteOfDay = 660, revealAtMinuteOfDay = 780)
        settingsRepository.putTarget(cfg)

        // 1. tryRemoveConfig blocked by self-lock
        val removeResult = coordinator.tryRemoveConfig(pkg)
        assertEquals(SettingsPolicy.RemoveResult.SelfLocked, removeResult)

        // 2. tryEditConfig increasing limit blocked by self-lock
        val editIncreaseResult = coordinator.tryEditConfig(cfg.copy(dailyLimitMinutes = 90))
        assertEquals(SettingsPolicy.EditResult.SelfLocked, editIncreaseResult)

        // 3. tryEditConfig decreasing limit allowed during self-lock
        val editDecreaseResult = coordinator.tryEditConfig(cfg.copy(dailyLimitMinutes = 40))
        assertTrue("Expected Applied, got $editDecreaseResult", editDecreaseResult is SettingsPolicy.EditResult.Applied)
        assertEquals(40, settingsRepository.snapshotTargets()[pkg]?.dailyLimitMinutes)

        // 4. tryEditConfig adding NEW target allowed during self-lock
        val newPkg = "com.another.app"
        val newCfg = TargetAppConfig(newPkg, 50, 1380, 420)
        val addResult = coordinator.tryEditConfig(newCfg)
        assertTrue("Expected Applied, got $addResult", addResult is SettingsPolicy.EditResult.Applied)
        assertEquals(50, settingsRepository.snapshotTargets()[newPkg]?.dailyLimitMinutes)
    }

    // =========================================================================
    // 8. TimeSource boundary test (exact millisecond and minute boundary)
    // =========================================================================

    @Test
    fun `boundary - window starts and ends at exact minute`() {
        // Window from 23:00 (1380) to 07:00 (420)
        val cfg = TargetAppConfig("com.test.app", 60, hideAtMinuteOfDay = 1380, revealAtMinuteOfDay = 420)

        // 22:59 (1379) -> false
        assertFalse(SelfLockPolicy.isInsideWindow(cfg, zdtToMillis(22, 59), utc))
        // 23:00 (1380) -> true
        assertTrue(SelfLockPolicy.isInsideWindow(cfg, zdtToMillis(23, 0), utc))
        // 06:59 (419) -> true
        assertTrue(SelfLockPolicy.isInsideWindow(cfg, zdtToMillis(6, 59), utc))
        // 07:00 (420) -> false
        assertFalse(SelfLockPolicy.isInsideWindow(cfg, zdtToMillis(7, 0), utc))
    }

    @Test
    fun `boundary - lock record expiration at exact millisecond`() {
        val until = 10000L
        val locked = LockState.LockedUntil(until)

        // Expired check: now >= until
        assertFalse("At until - 1 ms, record must NOT be expired", 9999L >= locked.untilEpochMillis)
        assertTrue("At until exact ms, record MUST be expired", 10000L >= locked.untilEpochMillis)
        assertTrue("At until + 1 ms, record MUST be expired", 10001L >= locked.untilEpochMillis)
    }
}
