package com.limiter.app.policy

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.limiter.app.data.CriticalPackageResolver
import com.limiter.app.data.InMemoryLockStateStore
import com.limiter.app.data.SettingsRepository
import com.limiter.app.dpm.AppFreezer
import com.limiter.app.dpm.DpmOperationResult
import com.limiter.app.dpm.FakeDpmGateway
import com.limiter.app.model.LockState
import com.limiter.app.model.PendingChange
import com.limiter.app.model.TargetAppConfig
import com.limiter.app.time.FakeTimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class LockCoordinatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var gateway: FakeDpmGateway
    private lateinit var freezer: AppFreezer
    private lateinit var lockStateStore: InMemoryLockStateStore
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var timeSource: FakeTimeSource
    private lateinit var resolver: FakeCriticalPackageResolver
    private lateinit var coordinator: LockCoordinator

    private val utc = ZoneId.of("UTC")
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

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
        
        val dataStoreFile = tempFolder.newFile("test_settings.preferences_pb")
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

    @Test
    fun `reconcile - not device owner returns NotDeviceOwner`() = runTest {
        gateway.deviceOwner = false
        val result = coordinator.reconcile()
        assertEquals(LockCoordinator.ReconcileResult.NotDeviceOwner, result)
    }

    @Test
    fun `reconcile - active lock repairs physical state`() = runTest {
        val pkg = "com.example.app"
        gateway.installedPackages.add(pkg)
        lockStateStore.ensureRecord(pkg)
        lockStateStore.lockUntilAtLeast(pkg, zdtToMillis(13, 0))
        
        // Physical state is tampered (not hidden, not suspended)
        
        val result = coordinator.reconcile()
        assertTrue(result is LockCoordinator.ReconcileResult.Ok)
        
        // Should be physically frozen now
        assertTrue(gateway.hiddenPackages.contains(pkg))
        assertTrue(gateway.suspendedPackages.contains(pkg))
    }

    @Test
    fun `reconcile - expired lock triggers unfreeze and unlocks`() = runTest {
        val pkg = "com.example.app"
        gateway.installedPackages.add(pkg)
        gateway.hiddenPackages.add(pkg)
        gateway.suspendedPackages.add(pkg)
        
        lockStateStore.ensureRecord(pkg)
        lockStateStore.lockUntilAtLeast(pkg, zdtToMillis(11, 0)) // expired
        
        val result = coordinator.reconcile()
        assertTrue(result is LockCoordinator.ReconcileResult.Ok)
        
        val records = lockStateStore.snapshot()
        assertTrue(records[pkg]?.state is LockState.Unlocked)
        
        assertFalse(gateway.hiddenPackages.contains(pkg))
        assertFalse(gateway.suspendedPackages.contains(pkg))
    }

    @Test
    fun `reconcile - failed unfreeze retains lock and schedules retry`() = runTest {
        val pkg = "com.example.app"
        gateway.installedPackages.add(pkg)
        gateway.hiddenPackages.add(pkg)
        gateway.suspendedPackages.add(pkg)
        
        lockStateStore.ensureRecord(pkg)
        lockStateStore.lockUntilAtLeast(pkg, zdtToMillis(11, 0)) // expired
        
        // Make unfreeze fail
        gateway.unhideFailPackages.add(pkg)
        
        coordinator.reconcile()
        
        val records = lockStateStore.snapshot()
        val record = records[pkg]!!
        assertTrue("Expected still locked, got ${record.state}", record.state is LockState.LockedUntil)
        assertEquals(1, record.unlockFailureCount)
        assertNotNull(record.nextUnlockRetryAtMillis)
        
        // Fast forward but not enough for retry
        timeSource.setNowMillis(timeSource.nowMillis() + 10_000)
        gateway.unhideFailPackages.remove(pkg) // try to make it pass, but it shouldn't even try
        coordinator.reconcile()
        
        val record2 = lockStateStore.snapshot()[pkg]!!
        assertTrue(record2.state is LockState.LockedUntil)
        assertEquals(1, record2.unlockFailureCount) // No attempt made
        
        // Fast forward past retry
        timeSource.setNowMillis(timeSource.nowMillis() + 30_000)
        coordinator.reconcile()
        
        val record3 = lockStateStore.snapshot()[pkg]!!
        assertTrue(record3.state is LockState.Unlocked)
        assertEquals(0, record3.unlockFailureCount)
    }

    @Test
    fun `onLimitReached - locks application and extends lock`() = runTest {
        val pkg = "com.example.app"
        gateway.installedPackages.add(pkg)
        
        val config = TargetAppConfig(pkg, 60, 1320, 420)
        settingsRepository.putTarget(config)
        
        timeSource.setNowMillis(zdtToMillis(12, 0)) // Noon
        
        coordinator.onLimitReached(pkg, 60 * 60_000L)
        
        val records = lockStateStore.snapshot()
        val record = records[pkg]!!
        assertTrue(record.state is LockState.LockedUntil)
        
        // Next midnight is 24:00, next reveal is 07:00 tomorrow
        // until should be max of both, so 07:00 tomorrow
        val lockedUntil = (record.state as LockState.LockedUntil).untilEpochMillis
        val nextReveal = ZonedDateTime.of(2024, 6, 16, 7, 0, 0, 0, utc).toInstant().toEpochMilli()
        assertEquals(nextReveal, lockedUntil)
        
        assertTrue(gateway.hiddenPackages.contains(pkg))
    }

    @Test
    fun `reconcile - protects packages even if locked`() = runTest {
        val pkg = "com.example.app"
        gateway.installedPackages.add(pkg)
        gateway.hiddenPackages.add(pkg)
        gateway.suspendedPackages.add(pkg)
        
        lockStateStore.ensureRecord(pkg)
        lockStateStore.lockUntilAtLeast(pkg, zdtToMillis(13, 0)) // active lock
        
        // Package becomes protected
        resolver.protectedPackages.add(pkg)
        
        coordinator.reconcile()
        
        // Should NOT unlock early
        assertTrue(gateway.hiddenPackages.contains(pkg))
        
        // Fast forward to expiry
        timeSource.setNowMillis(zdtToMillis(14, 0))
        coordinator.reconcile()
        
        // Now it should unlock
        assertFalse(gateway.hiddenPackages.contains(pkg))
        
        // But should not be re-locked by schedule
        val config = TargetAppConfig(pkg, 60, 0, 1439) // all-day lock
        settingsRepository.putTarget(config)
        coordinator.reconcile()
        assertFalse(gateway.hiddenPackages.contains(pkg)) // still not locked because protected
    }

    @Test
    fun `reconcile - missing packages are cleared`() = runTest {
        val pkg = "com.example.app"
        // NOT added to installedPackages
        
        lockStateStore.ensureRecord(pkg)
        lockStateStore.lockUntilAtLeast(pkg, zdtToMillis(13, 0))
        
        coordinator.reconcile()
        
        val records = lockStateStore.snapshot()
        assertFalse(records.containsKey(pkg))
    }

    @Test
    fun `reconcile - active lock on configured target is NOT destroyed by transient missing detection`() = runTest {
        val pkg = "com.example.app"
        // Configured as an active target
        val config = TargetAppConfig(pkg, 60, 1320, 420)
        settingsRepository.putTarget(config)
        
        // Active lock until 13:00 (now is 12:00)
        lockStateStore.ensureRecord(pkg)
        val activeUntil = zdtToMillis(13, 0)
        lockStateStore.lockUntilAtLeast(pkg, activeUntil)
        
        // Simulating transient visibility/detection failure where isPackageInstalled returns false
        // (pkg NOT in gateway.installedPackages)
        
        val result = coordinator.reconcile()
        assertTrue(result is LockCoordinator.ReconcileResult.Ok)
        
        // Active lock must NOT be deleted or destroyed
        val records = lockStateStore.snapshot()
        val record = records[pkg]
        assertNotNull("Record should still exist", record)
        assertTrue("State should still be LockedUntil", record?.state is LockState.LockedUntil)
        assertEquals(activeUntil, (record?.state as LockState.LockedUntil).untilEpochMillis)
    }

    @Test
    fun `reconcile - partial physical state is repaired to dual frozen`() = runTest {
        val pkg = "com.example.app"
        gateway.installedPackages.add(pkg)
        lockStateStore.ensureRecord(pkg)
        lockStateStore.lockUntilAtLeast(pkg, zdtToMillis(13, 0))
        
        // Partial physical state: hidden is true, but suspended is false
        gateway.hiddenPackages.add(pkg)
        gateway.suspendedPackages.remove(pkg)
        
        val result = coordinator.reconcile()
        assertTrue(result is LockCoordinator.ReconcileResult.Ok)
        
        // Invariant: both hidden AND suspended must be enforced
        assertTrue(gateway.hiddenPackages.contains(pkg))
        assertTrue(gateway.suspendedPackages.contains(pkg))
    }

    @Test
    fun `onLimitReached - lock deadline is never shortened`() = runTest {
        val pkg = "com.example.app"
        gateway.installedPackages.add(pkg)
        val config = TargetAppConfig(pkg, 60, 1320, 420) // reveal 07:00
        settingsRepository.putTarget(config)
        
        // Suppose lock was already set far in advance (e.g. 3 days later)
        val farFutureDeadline = zdtToMillis(12, 0) + 3 * 86_400_000L
        lockStateStore.ensureRecord(pkg)
        lockStateStore.lockUntilAtLeast(pkg, farFutureDeadline)
        
        // Trigger limit reached
        coordinator.onLimitReached(pkg, 60 * 60_000L)
        
        val records = lockStateStore.snapshot()
        val record = records[pkg]!!
        assertTrue(record.state is LockState.LockedUntil)
        assertEquals("Deadline must not shorten", farFutureDeadline, (record.state as LockState.LockedUntil).untilEpochMillis)
    }

    @Test
    fun `reconcile - repeated reconciliation while locked is idempotent and preserves deadline`() = runTest {
        val pkg = "com.example.app"
        gateway.installedPackages.add(pkg)
        val config = TargetAppConfig(pkg, 60, 1320, 420)
        settingsRepository.putTarget(config)
        
        val activeUntil = zdtToMillis(15, 0)
        lockStateStore.ensureRecord(pkg)
        lockStateStore.lockUntilAtLeast(pkg, activeUntil)
        
        // Multiple reconcile invocations (simulating duplicate alarms or events)
        coordinator.reconcile()
        coordinator.reconcile()
        coordinator.reconcile()
        
        val records = lockStateStore.snapshot()
        val record = records[pkg]!!
        assertTrue(record.state is LockState.LockedUntil)
        assertEquals(activeUntil, (record.state as LockState.LockedUntil).untilEpochMillis)
        assertTrue(gateway.hiddenPackages.contains(pkg))
        assertTrue(gateway.suspendedPackages.contains(pkg))
    }

    @Test
    fun `reconcile - process restart restores dual physical freeze from persisted store`() = runTest {
        val pkg = "com.example.app"
        gateway.installedPackages.add(pkg)
        val config = TargetAppConfig(pkg, 60, 1320, 420)
        settingsRepository.putTarget(config)
        
        // Simulate pre-existing persisted lock record from previous process run
        val activeUntil = zdtToMillis(16, 0)
        lockStateStore.ensureRecord(pkg)
        lockStateStore.lockUntilAtLeast(pkg, activeUntil)
        
        // At fresh process start, physical state might not be frozen yet
        gateway.hiddenPackages.remove(pkg)
        gateway.suspendedPackages.remove(pkg)
        
        // First reconciliation after launch
        val result = coordinator.reconcile()
        assertTrue(result is LockCoordinator.ReconcileResult.Ok)
        
        // Verified: both hidden AND suspended are restored
        assertTrue(gateway.hiddenPackages.contains(pkg))
        assertTrue(gateway.suspendedPackages.contains(pkg))
        assertEquals(activeUntil, (lockStateStore.snapshot()[pkg]!!.state as LockState.LockedUntil).untilEpochMillis)
    }

    @Test
    fun `reconcile - local epoch-day rollover applies eligible pending changes in Phase E`() = runTest {
        val pkg = "com.example.app"
        gateway.installedPackages.add(pkg)
        val initialConfig = TargetAppConfig(pkg, 60, 1320, 420)
        settingsRepository.putTarget(initialConfig)
        
        // Desired loosened config (120 mins) eligible tomorrow
        val tomorrowEpochDay = com.limiter.app.time.TimeMath.localEpochDay(timeSource.nowMillis(), utc) + 1
        val loosenedConfig = TargetAppConfig(pkg, 120, 1320, 420)
        val pending = PendingChange(pkg, loosenedConfig, tomorrowEpochDay)
        settingsRepository.putPending(pending)
        
        // Day 1: not eligible yet
        coordinator.reconcile()
        assertEquals(60, settingsRepository.snapshotTargets()[pkg]?.dailyLimitMinutes)
        assertEquals(1, settingsRepository.snapshotPending().size)
        
        // Rollover to tomorrow: advance time by 24 hours
        timeSource.setNowMillis(timeSource.nowMillis() + 86_400_000L)
        
        // Reconcile after midnight rollover
        coordinator.reconcile()
        
        // Verified: pending change applied to targets and cleared from pending
        assertEquals(120, settingsRepository.snapshotTargets()[pkg]?.dailyLimitMinutes)
        assertTrue(settingsRepository.snapshotPending().isEmpty())
    }

    @Test
    fun `reconcile - target outside lock window remains unlocked and unfrozen`() = runTest {
        val pkg = "com.example.app"
        gateway.installedPackages.add(pkg)
        // 22:00 to 07:00 (1320 to 420), current time is 12:00 (outside window)
        val config = TargetAppConfig(pkg, 60, 1320, 420)
        settingsRepository.putTarget(config)

        val result = coordinator.reconcile()
        assertTrue(result is LockCoordinator.ReconcileResult.Ok)

        val records = lockStateStore.snapshot()
        val record = records[pkg]
        assertTrue(record == null || record.state is LockState.Unlocked)
        assertFalse(gateway.hiddenPackages.contains(pkg))
        assertFalse(gateway.suspendedPackages.contains(pkg))
    }

    @Test
    fun `reconcile - partial physical state suspended only is repaired to dual frozen`() = runTest {
        val pkg = "com.example.app"
        gateway.installedPackages.add(pkg)
        lockStateStore.ensureRecord(pkg)
        lockStateStore.lockUntilAtLeast(pkg, zdtToMillis(13, 0))

        // Partial physical state: suspended is true, but hidden is false
        gateway.suspendedPackages.add(pkg)
        gateway.hiddenPackages.remove(pkg)

        val result = coordinator.reconcile()
        assertTrue(result is LockCoordinator.ReconcileResult.Ok)

        // Invariant: both hidden AND suspended must be enforced
        assertTrue(gateway.hiddenPackages.contains(pkg))
        assertTrue(gateway.suspendedPackages.contains(pkg))
    }

    @Test
    fun `reconcile - temporary device owner loss leaves physical state untouched and performs no mutations`() = runTest {
        val pkg = "com.example.app"
        gateway.installedPackages.add(pkg)
        // In window: 11:00 to 13:00 (now is 12:00)
        val inWindowConfig = TargetAppConfig(pkg, 60, 660, 780)
        settingsRepository.putTarget(inWindowConfig)

        // Device Owner lost
        gateway.deviceOwner = false

        val result = coordinator.reconcile()
        assertEquals(LockCoordinator.ReconcileResult.NotDeviceOwner, result)

        // No lock records created, no apps frozen
        assertTrue(lockStateStore.snapshot().isEmpty())
        assertFalse(gateway.hiddenPackages.contains(pkg))
        assertFalse(gateway.suspendedPackages.contains(pkg))
    }

    // -----------------------------------------------------------------------
    // Pre-R2 Critical Hotfix Regression Tests
    // -----------------------------------------------------------------------

    @Test
    fun `regression - independent restoration failure unhide fails while unsuspend succeeds`() = runTest {
        val pkg = "com.example.app"
        gateway.installedPackages.add(pkg)
        gateway.hiddenPackages.add(pkg)
        gateway.suspendedPackages.add(pkg)

        lockStateStore.ensureRecord(pkg)
        lockStateStore.lockUntilAtLeast(pkg, zdtToMillis(11, 0)) // expired at 11:00 (now is 12:00)

        // Make unhide fail, but unsuspend will succeed
        gateway.unhideFailPackages.add(pkg)

        val result = coordinator.reconcile()
        assertTrue(result is LockCoordinator.ReconcileResult.Ok)

        // Unsuspend succeeded independently, unhide failed
        assertFalse("Package should be unsuspended", gateway.suspendedPackages.contains(pkg))
        assertTrue("Package should still be hidden", gateway.hiddenPackages.contains(pkg))

        // Record must retain lock, schedule retry, and persist partial restoration progress
        val record = lockStateStore.snapshot()[pkg]!!
        assertEquals(1, record.unlockFailureCount)
        assertNotNull(record.nextUnlockRetryAtMillis)
        assertTrue("unsuspensionRestored should be true", record.unsuspensionRestored)
        assertFalse("visibilityRestored should be false", record.visibilityRestored)
    }

    @Test
    fun `regression - independent restoration failure unsuspend fails while unhide succeeds`() = runTest {
        val pkg = "com.example.app"
        gateway.installedPackages.add(pkg)
        gateway.hiddenPackages.add(pkg)
        gateway.suspendedPackages.add(pkg)

        lockStateStore.ensureRecord(pkg)
        lockStateStore.lockUntilAtLeast(pkg, zdtToMillis(11, 0)) // expired

        // Make unsuspend fail, but unhide will succeed
        gateway.unsuspendFailPackages.add(pkg)

        val result = coordinator.reconcile()
        assertTrue(result is LockCoordinator.ReconcileResult.Ok)

        // Unhide succeeded independently, unsuspend failed
        assertFalse("Package should be unhidden", gateway.hiddenPackages.contains(pkg))
        assertTrue("Package should still be suspended", gateway.suspendedPackages.contains(pkg))

        val record = lockStateStore.snapshot()[pkg]!!
        assertEquals(1, record.unlockFailureCount)
        assertNotNull(record.nextUnlockRetryAtMillis)
        assertTrue("visibilityRestored should be true", record.visibilityRestored)
        assertFalse("unsuspensionRestored should be false", record.unsuspensionRestored)
    }

    @Test
    fun `regression - retry only retries incomplete operations and transitions to Unlocked`() = runTest {
        val pkg = "com.example.app"
        gateway.installedPackages.add(pkg)
        gateway.hiddenPackages.add(pkg)
        gateway.suspendedPackages.add(pkg)

        lockStateStore.ensureRecord(pkg)
        lockStateStore.lockUntilAtLeast(pkg, zdtToMillis(11, 0))

        // Attempt 1: unhide fails, unsuspend succeeds
        gateway.unhideFailPackages.add(pkg)
        coordinator.reconcile()

        val record1 = lockStateStore.snapshot()[pkg]!!
        assertTrue(record1.unsuspensionRestored)
        assertFalse(record1.visibilityRestored)

        // Fast-forward past backoff
        timeSource.setNowMillis(record1.nextUnlockRetryAtMillis!! + 1000)
        gateway.unhideFailPackages.remove(pkg) // Now unhide will succeed

        // If unsuspend were erroneously re-called, it would fail
        gateway.unsuspendFailPackages.add(pkg)

        // Attempt 2: retry should ONLY execute unhide
        coordinator.reconcile()

        val record2 = lockStateStore.snapshot()[pkg]!!
        assertTrue("Should transition to Unlocked", record2.state is LockState.Unlocked)
        assertEquals(0, record2.unlockFailureCount)
        assertNull(record2.nextUnlockRetryAtMillis)

        // Physical state verified: both restored
        assertFalse(gateway.hiddenPackages.contains(pkg))
        assertFalse(gateway.suspendedPackages.contains(pkg))
    }

    @Test
    fun `regression - stale early alarm does not unlock active lock or shorten deadline`() = runTest {
        val pkg = "com.example.app"
        gateway.installedPackages.add(pkg)
        val config = TargetAppConfig(pkg, 60, 1320, 420)
        settingsRepository.putTarget(config)

        val activeUntil = zdtToMillis(18, 0)
        lockStateStore.ensureRecord(pkg)
        lockStateStore.lockUntilAtLeast(pkg, activeUntil)
        freezer.freeze(pkg)

        // Trigger reconciliation at 12:05 (stale alarm / early check)
        timeSource.setNowMillis(zdtToMillis(12, 5))
        val result = coordinator.reconcile()
        assertTrue(result is LockCoordinator.ReconcileResult.Ok)

        // Deadline must be strictly preserved
        val record = lockStateStore.snapshot()[pkg]!!
        assertTrue(record.state is LockState.LockedUntil)
        assertEquals(activeUntil, (record.state as LockState.LockedUntil).untilEpochMillis)

        // Physical freeze must be strictly preserved
        assertTrue(gateway.hiddenPackages.contains(pkg))
        assertTrue(gateway.suspendedPackages.contains(pkg))
    }

    @Test
    fun `regression - target removed while locked defers removal, on expiry restores app and applies removal`() = runTest {
        val pkg = "com.example.app"
        gateway.installedPackages.add(pkg)
        val config = TargetAppConfig(pkg, 60, 1320, 420)
        settingsRepository.putTarget(config)

        val activeUntil = zdtToMillis(13, 0)
        lockStateStore.ensureRecord(pkg)
        lockStateStore.lockUntilAtLeast(pkg, activeUntil)
        freezer.freeze(pkg)

        // User removes target while locked
        val removeResult = coordinator.tryRemoveConfig(pkg)
        assertTrue("Removal while locked must be deferred", removeResult is SettingsPolicy.RemoveResult.Deferred)

        // Reconcile while still locked (12:30): lock is active, target remains in targets
        timeSource.setNowMillis(zdtToMillis(12, 30))
        coordinator.reconcile()
        assertTrue(gateway.hiddenPackages.contains(pkg))
        assertTrue(gateway.suspendedPackages.contains(pkg))
        assertTrue(settingsRepository.snapshotTargets().containsKey(pkg))

        // Expiry at 13:01
        timeSource.setNowMillis(zdtToMillis(13, 1))
        // Next day for eligibility authority:
        // Set date to tomorrow so pending change is eligible
        timeSource.setNowMillis(timeSource.nowMillis() + 86_400_000L)

        coordinator.reconcile()

        // Restored physically
        assertFalse(gateway.hiddenPackages.contains(pkg))
        assertFalse(gateway.suspendedPackages.contains(pkg))

        // Deferred removal is applied
        assertFalse(settingsRepository.snapshotTargets().containsKey(pkg))

        // Next reconciliation must NEVER re-freeze removed target
        coordinator.reconcile()
        assertFalse(gateway.hiddenPackages.contains(pkg))
        assertFalse(gateway.suspendedPackages.contains(pkg))
    }

    @Test
    fun `regression - reconciliation never re-freezes package undergoing release`() = runTest {
        val pkg = "com.example.app"
        gateway.installedPackages.add(pkg)
        // Window is 12:00 to 14:00 (active window)
        val config = TargetAppConfig(pkg, 60, 720, 840)
        settingsRepository.putTarget(config)

        lockStateStore.ensureRecord(pkg)
        lockStateStore.lockUntilAtLeast(pkg, zdtToMillis(11, 0)) // expired daily limit lock

        // Unfreeze fails partially: unhide fails, unsuspend succeeds
        gateway.unhideFailPackages.add(pkg)
        gateway.hiddenPackages.add(pkg)
        gateway.suspendedPackages.add(pkg)

        coordinator.reconcile()

        // Package is undergoing release (unsuspensionRestored = true, visibilityRestored = false)
        val record = lockStateStore.snapshot()[pkg]!!
        assertEquals(1, record.unlockFailureCount)

        // Even though current time is inside scheduled lock window [12:00, 14:00),
        // reconciliation must NOT re-freeze or re-suspend package!
        coordinator.reconcile()
        assertFalse("Reconciliation must not re-suspend package undergoing release", gateway.suspendedPackages.contains(pkg))

        // onLimitReached must also NOT re-freeze
        coordinator.onLimitReached(pkg, 3600_000L)
        assertFalse("onLimitReached must not re-suspend package undergoing release", gateway.suspendedPackages.contains(pkg))
    }

    @Test
    fun `regression - process restart preserves partial progress and continues bounded retry`() = runTest {
        val pkg = "com.example.app"
        gateway.installedPackages.add(pkg)
        gateway.hiddenPackages.add(pkg)
        gateway.suspendedPackages.add(pkg)

        lockStateStore.ensureRecord(pkg)
        lockStateStore.lockUntilAtLeast(pkg, zdtToMillis(11, 0))

        // Initial attempt fails unhide
        gateway.unhideFailPackages.add(pkg)
        coordinator.reconcile()

        val recordBeforeRestart = lockStateStore.snapshot()[pkg]!!
        assertTrue(recordBeforeRestart.unsuspensionRestored)
        assertFalse(recordBeforeRestart.visibilityRestored)
        assertEquals(1, recordBeforeRestart.unlockFailureCount)

        // Simulate process termination and restart with a new LockCoordinator instance
        val freshCoordinator = LockCoordinator(
            gateway = gateway,
            freezer = freezer,
            lockStateStore = lockStateStore,
            settingsRepository = settingsRepository,
            criticalPackageResolver = resolver,
            timeSource = timeSource
        )

        // Before backoff: no retry attempt
        freshCoordinator.reconcile()
        assertEquals(1, lockStateStore.snapshot()[pkg]!!.unlockFailureCount)

        // After backoff: unhide succeeds, unsuspend is NOT re-attempted
        timeSource.setNowMillis(recordBeforeRestart.nextUnlockRetryAtMillis!! + 5000)
        gateway.unhideFailPackages.remove(pkg)
        gateway.unsuspendFailPackages.add(pkg) // would fail if called

        freshCoordinator.reconcile()

        val finalRecord = lockStateStore.snapshot()[pkg]!!
        assertTrue(finalRecord.state is LockState.Unlocked)
        assertFalse(gateway.hiddenPackages.contains(pkg))
        assertFalse(gateway.suspendedPackages.contains(pkg))
    }

    @Test
    fun `regression - active restriction on ir divar is preserved with midnight deadline`() = runTest {
        val pkg = "ir.divar"
        gateway.installedPackages.add(pkg)
        gateway.hiddenPackages.add(pkg)
        gateway.suspendedPackages.add(pkg)

        val divarConfig = TargetAppConfig(pkg, 60, 1380, 420)
        settingsRepository.putTarget(divarConfig)

        // Exact deadline on device: Oct 12, 2026 00:00 Iran time (1791750600000L)
        val midnightDeadline = 1791750600000L
        timeSource.setNowMillis(midnightDeadline - 3600_000L) // 1 hour before midnight

        lockStateStore.ensureRecord(pkg)
        lockStateStore.lockUntilAtLeast(pkg, midnightDeadline)

        // Reconcile before midnight: active lock must NOT be unlocked or shortened
        coordinator.reconcile()

        val recordBefore = lockStateStore.snapshot()[pkg]!!
        assertTrue(recordBefore.state is LockState.LockedUntil)
        assertEquals(midnightDeadline, (recordBefore.state as LockState.LockedUntil).untilEpochMillis)
        assertTrue(gateway.hiddenPackages.contains(pkg))
        assertTrue(gateway.suspendedPackages.contains(pkg))

        // Advance to 1 second past midnight (expiry)
        timeSource.setNowMillis(midnightDeadline + 1000L)

        coordinator.reconcile()

        // ir.divar must now be restored
        val recordAfter = lockStateStore.snapshot()[pkg]!!
        assertTrue("ir.divar should be Unlocked after expiry", recordAfter.state is LockState.Unlocked)
        assertFalse("ir.divar must be unhidden", gateway.hiddenPackages.contains(pkg))
        assertFalse("ir.divar must be unsuspended", gateway.suspendedPackages.contains(pkg))
    }
}

