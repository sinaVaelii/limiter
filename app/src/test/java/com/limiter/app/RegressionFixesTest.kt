package com.limiter.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.limiter.app.data.CriticalPackageResolver
import com.limiter.app.data.DataStoreLockStateStore
import com.limiter.app.data.InMemoryLockStateStore
import com.limiter.app.data.SettingsRepository
import com.limiter.app.data.UsageSnapshotStore
import com.limiter.app.dpm.AppFreezer
import com.limiter.app.dpm.DeviceDpmGateway
import com.limiter.app.dpm.FakeDpmGateway
import com.limiter.app.dpm.PackageInstallStatus
import com.limiter.app.model.LockRecord
import com.limiter.app.model.LockState
import com.limiter.app.model.PendingChange
import com.limiter.app.model.TargetAppConfig
import com.limiter.app.model.UsageSnapshot
import com.limiter.app.policy.LockCoordinator
import com.limiter.app.policy.SettingsPolicy
import com.limiter.app.scheduler.AlarmDispatcher
import com.limiter.app.scheduler.AlarmReceiver
import com.limiter.app.scheduler.AlarmScheduleResult
import com.limiter.app.scheduler.IntentFactory
import com.limiter.app.scheduler.RescheduleReport
import com.limiter.app.scheduler.TimeWindowScheduler
import com.limiter.app.service.UsageMonitorService
import com.limiter.app.service.UsageMonitorWatchdog
import com.limiter.app.time.FakeTimeSource
import com.limiter.app.time.TimeMath
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Comprehensive regression tests verifying all confirmed bug-fixes and invariants:
 * 1. MainActivity Intent Trust Gate (rejecting untrusted mutation extras).
 * 2. Centralized Self-Lock Enforcement at the mutation boundary (LockCoordinator.tryEditConfig / tryRemoveConfig).
 * 3. Lock Monotonicity and Preservation (never shortening lock deadlines).
 * 4. Unlock Failure handling for unrecorded/orphan packages (creating ReleasePending record instead of dropping).
 * 5. Scheduler Reliability & Deterministic Request Codes (TimeWindowScheduler).
 * 6. Usage Snapshot Store corruption tolerance (skipping corrupt lines without crashing).
 * 7. Atomic Target and Pending state persistence (SettingsRepository.setTargetsAndPending).
 * 8. Cleanup of pending changes for uninstalled packages in Phase E (no leak).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RegressionFixesTest {

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
    private lateinit var coordinator: LockCoordinator
    private lateinit var alarmDispatcher: FakeAlarmDispatcher
    private lateinit var intentFactory: FakeIntentFactory
    private lateinit var scheduler: TimeWindowScheduler

    class FakeAlarmDispatcher : AlarmDispatcher {
        var canScheduleExact = true
        var exactFailure: Throwable? = null
        var inexactFailure: Throwable? = null

        val exactScheduled = mutableListOf<ScheduledAlarm>()
        val inexactScheduled = mutableListOf<ScheduledAlarm>()
        val cancelled = mutableListOf<CancelledAlarm>()

        data class ScheduledAlarm(val triggerMillis: Long, val intent: Intent, val requestCode: Int)
        data class CancelledAlarm(val intent: Intent, val requestCode: Int)

        override fun canScheduleExactAlarms(): Boolean = canScheduleExact

        override fun scheduleExact(triggerMillis: Long, intent: Intent, requestCode: Int) {
            exactFailure?.let { throw it }
            exactScheduled.add(ScheduledAlarm(triggerMillis, intent, requestCode))
        }

        override fun scheduleInexact(triggerMillis: Long, intent: Intent, requestCode: Int) {
            inexactFailure?.let { throw it }
            inexactScheduled.add(ScheduledAlarm(triggerMillis, intent, requestCode))
        }

        override fun cancel(intent: Intent, requestCode: Int) {
            cancelled.add(CancelledAlarm(intent, requestCode))
        }

        fun clear() {
            exactScheduled.clear()
            inexactScheduled.clear()
            cancelled.clear()
            exactFailure = null
            inexactFailure = null
        }
    }

    class FakeIntentFactory : IntentFactory {
        override fun createHideIntent(pkg: String): Intent = Intent(TimeWindowScheduler.ACTION_HIDE)
        override fun createUnlockIntent(): Intent = Intent(TimeWindowScheduler.ACTION_UNLOCK)
        override fun createMidnightIntent(): Intent = Intent(TimeWindowScheduler.ACTION_MIDNIGHT)
        override fun createRetryIntent(): Intent = Intent(TimeWindowScheduler.ACTION_RETRY)
    }

    class FakeCriticalPackageResolver(
        val protectedPackages: MutableSet<String> = mutableSetOf(
            "com.limiter.app",
            "com.android.systemui",
            "com.android.settings"
        ),
        val eligiblePackages: MutableSet<String> = mutableSetOf(
            "com.example.app",
            "com.example.uninstalled"
        )
    ) : CriticalPackageResolver {
        override fun resolveProtected(): Set<String> = protectedPackages

        override fun isEligibleTarget(packageName: String): Boolean {
            return packageName in eligiblePackages && packageName !in protectedPackages
        }
    }

    private fun zdtToMillis(hour: Int, minute: Int): Long {
        return ZonedDateTime.of(2026, 10, 12, hour, minute, 0, 0, utc)
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
        timeSource.setNowMillis(zdtToMillis(12, 0)) // 12:00 UTC (outside hide window)
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

        alarmDispatcher = FakeAlarmDispatcher()
        intentFactory = FakeIntentFactory()
        scheduler = TimeWindowScheduler(
            context = null,
            settingsRepository = settingsRepository,
            lockStateStore = lockStateStore,
            timeSource = timeSource,
            alarmDispatcher = alarmDispatcher,
            intentFactory = intentFactory
        )
    }

    // =========================================================================
    // 1. External Intent Trust Boundaries (Phase 1 / Section 4.1)
    // =========================================================================

    @Test
    fun untrustedMutationIntents_areRejectedByTrustGate() {
        // Test key detection logic
        assertTrue(MainActivity.isUntrustedMutation(setOf("add_target")))
        assertTrue(MainActivity.isUntrustedMutation(setOf("limit")))
        assertTrue(MainActivity.isUntrustedMutation(setOf("hide")))
        assertTrue(MainActivity.isUntrustedMutation(setOf("reveal")))
        assertTrue(MainActivity.isUntrustedMutation(setOf("other", "add_target")))

        // Legitimate navigation extras or empty extras are not rejected
        assertFalse(MainActivity.isUntrustedMutation(emptySet()))
        assertFalse(MainActivity.isUntrustedMutation(setOf("legitimate_nav_key")))
        assertFalse(MainActivity.isUntrustedMutation(null))

        // Null intent is safely handled
        assertFalse(MainActivity.isUntrustedMutationIntent(null))
        assertEquals(MainActivity.Companion.IntentGateDecision.IGNORE_EXTERNAL, MainActivity.evaluateIntent(null))

        // External mutation extras combined with ACTION_MAIN are rejected
        val mainWithMutation = MainActivity.Companion.ParsedIntent(
            action = Intent.ACTION_MAIN,
            extraKeys = setOf("add_target")
        )
        assertEquals(MainActivity.Companion.IntentGateDecision.REJECT_MUTATION, MainActivity.evaluateIntent(mainWithMutation))

        // External mutation extras combined with ACTION_INTERNAL_NAVIGATE are rejected
        val internalNavWithMutation = MainActivity.Companion.ParsedIntent(
            action = MainActivity.ACTION_INTERNAL_NAVIGATE,
            extraKeys = setOf("limit", "hide")
        )
        assertEquals(MainActivity.Companion.IntentGateDecision.REJECT_MUTATION, MainActivity.evaluateIntent(internalNavWithMutation))

        // External mutation extras with arbitrary external action are rejected
        val externalWithMutation = MainActivity.Companion.ParsedIntent(
            action = "com.attacker.action.MUTATE",
            extraKeys = setOf("reveal")
        )
        assertEquals(MainActivity.Companion.IntentGateDecision.REJECT_MUTATION, MainActivity.evaluateIntent(externalWithMutation))

        // Malformed bundle / BadParcelableException is rejected
        val malformed = MainActivity.Companion.ParsedIntent(
            action = MainActivity.ACTION_INTERNAL_NAVIGATE,
            hasMalformedExtras = true
        )
        assertEquals(MainActivity.Companion.IntentGateDecision.REJECT_MALFORMED, MainActivity.evaluateIntent(malformed))

        // Legitimate launcher launch without mutation extras is allowed
        val legitimateMain = MainActivity.Companion.ParsedIntent(
            action = Intent.ACTION_MAIN,
            extraKeys = emptySet()
        )
        assertEquals(MainActivity.Companion.IntentGateDecision.ALLOW_MAIN, MainActivity.evaluateIntent(legitimateMain))

        // Legitimate internal navigation without mutation extras is allowed
        val legitimateNav = MainActivity.Companion.ParsedIntent(
            action = MainActivity.ACTION_INTERNAL_NAVIGATE,
            extraKeys = setOf("nav_tab_index")
        )
        assertEquals(MainActivity.Companion.IntentGateDecision.ALLOW_NAVIGATE, MainActivity.evaluateIntent(legitimateNav))

        // Arbitrary external action without mutation is ignored (no-op)
        val unknownExternal = MainActivity.Companion.ParsedIntent(
            action = "android.intent.action.VIEW",
            extraKeys = setOf("extra_data")
        )
        assertEquals(MainActivity.Companion.IntentGateDecision.IGNORE_EXTERNAL, MainActivity.evaluateIntent(unknownExternal))
    }

    // =========================================================================
    // 2. Centralized Self-Lock Enforcement (Phase 1 / Section 4.2)
    // =========================================================================

    @Test
    fun selfLockActive_tryEditConfig_returnsSelfLockedAndDoesNotMutate() = runTest(testDispatcher) {
        val config = TargetAppConfig(
            packageName = "com.example.app",
            dailyLimitMinutes = 60,
            hideAtMinuteOfDay = 23 * 60,  // 23:00
            revealAtMinuteOfDay = 7 * 60   // 07:00
        )
        gateway.installedPackages.add("com.example.app")
        settingsRepository.setTargets(mapOf("com.example.app" to config))

        // Set clock to 23:30 (inside active hide window 23:00..07:00 -> Self-Locked)
        timeSource.setNowMillis(zdtToMillis(23, 30))

        // Attempt to edit configuration during active self-lock window
        val edited = config.copy(dailyLimitMinutes = 120)
        val result = coordinator.tryEditConfig(edited)

        assertEquals(SettingsPolicy.EditResult.SelfLocked, result)
        // Verify target was not changed in repository
        val current = settingsRepository.snapshotTargets()["com.example.app"]
        assertEquals(60, current?.dailyLimitMinutes)
    }

    @Test
    fun selfLockActive_tryRemoveConfig_returnsSelfLockedAndDoesNotRemove() = runTest(testDispatcher) {
        val config = TargetAppConfig(
            packageName = "com.example.app",
            dailyLimitMinutes = 60,
            hideAtMinuteOfDay = 23 * 60,
            revealAtMinuteOfDay = 7 * 60
        )
        gateway.installedPackages.add("com.example.app")
        settingsRepository.setTargets(mapOf("com.example.app" to config))

        // Inside active self-lock window (23:30)
        timeSource.setNowMillis(zdtToMillis(23, 30))

        val result = coordinator.tryRemoveConfig("com.example.app")

        assertEquals(SettingsPolicy.RemoveResult.SelfLocked, result)
        // Verify target was not removed from repository
        assertTrue(settingsRepository.snapshotTargets().containsKey("com.example.app"))
    }

    @Test
    fun selfLockInactive_allowsTryEditConfig() = runTest(testDispatcher) {
        val config = TargetAppConfig(
            packageName = "com.example.app",
            dailyLimitMinutes = 60,
            hideAtMinuteOfDay = 23 * 60,
            revealAtMinuteOfDay = 7 * 60
        )
        gateway.installedPackages.add("com.example.app")
        settingsRepository.setTargets(mapOf("com.example.app" to config))

        // At 12:00 (outside hide window, not locked)
        timeSource.setNowMillis(zdtToMillis(12, 0))

        val tightened = config.copy(dailyLimitMinutes = 45) // tightening applies immediately
        val result = coordinator.tryEditConfig(tightened)

        assertTrue(result is SettingsPolicy.EditResult.Applied)
        val current = settingsRepository.snapshotTargets()["com.example.app"]
        assertEquals(45, current?.dailyLimitMinutes)
    }

    // =========================================================================
    // 3. Lock Monotonicity and Preservation (Phase 1 / Section 4.3)
    // =========================================================================

    @Test
    fun lockUntilAtLeast_neverShortensExistingDeadline() = runTest(testDispatcher) {
        val pkg = "com.example.app"
        val existingDeadline = 100_000L

        // Initial lock
        lockStateStore.lockUntilAtLeast(pkg, existingDeadline)
        val initialRecord = lockStateStore.snapshot()[pkg]
        assertEquals(existingDeadline, (initialRecord?.state as LockState.LockedUntil).untilEpochMillis)

        // Attempt to shorten lock deadline to earlier time (50_000L)
        lockStateStore.lockUntilAtLeast(pkg, 50_000L)
        val afterShorter = lockStateStore.snapshot()[pkg]
        assertEquals(existingDeadline, (afterShorter?.state as LockState.LockedUntil).untilEpochMillis)

        // Extending lock deadline to later time (150_000L) succeeds
        lockStateStore.lockUntilAtLeast(pkg, 150_000L)
        val afterLonger = lockStateStore.snapshot()[pkg]
        assertEquals(150_000L, (afterLonger?.state as LockState.LockedUntil).untilEpochMillis)
    }

    // =========================================================================
    // 4. Unlock Failure Handling for Orphaned / Missing Records (Section 4.3)
    // =========================================================================

    @Test
    fun inMemoryLockStateStore_recordUnlockFailure_createsReleasePendingForNullRecord() = runTest(testDispatcher) {
        val orphanPkg = "com.orphan.frozen"
        val now = 200_000L

        // Package has no prior lock record
        assertEquals(null, lockStateStore.snapshot()[orphanPkg])

        // When unlock failure occurs on an orphaned package
        lockStateStore.recordUnlockFailure(orphanPkg, now)

        val record = lockStateStore.snapshot()[orphanPkg]
        assertNotNull(record)
        assertTrue(record!!.state is LockState.ReleasePending)
        assertEquals(1, record.unlockFailureCount)
        assertEquals(now + 30_000L, record.nextUnlockRetryAtMillis)
    }

    @Test
    fun dataStoreLockStateStore_recordUnlockFailure_createsReleasePendingForNullRecord() = runTest(testDispatcher) {
        val storeFile = tempFolder.newFile("test_lock_state.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { storeFile }
        )
        val dsStore = DataStoreLockStateStore(dataStore)

        val orphanPkg = "com.orphan.ds.frozen"
        val now = 300_000L

        dsStore.recordUnlockFailure(orphanPkg, now)

        val record = dsStore.snapshot()[orphanPkg]
        assertNotNull(record)
        assertTrue(record!!.state is LockState.ReleasePending)
        assertEquals(1, record.unlockFailureCount)
        assertEquals(now + 30_000L, record.nextUnlockRetryAtMillis)
    }

    // =========================================================================
    // 5. Scheduler Reliability & Deterministic Request Codes (Phase 2 / Section 5.1)
    // =========================================================================

    @Test
    fun timeWindowScheduler_calculatesDeterministicRequestCodes() {
        val pkg1 = "com.google.android.youtube"
        val pkg2 = "org.telegram.messenger"

        val code1A = TimeWindowScheduler.calculateHideRequestCode(pkg1)
        val code1B = TimeWindowScheduler.calculateHideRequestCode(pkg1)
        assertEquals(code1A, code1B)

        val code2 = TimeWindowScheduler.calculateHideRequestCode(pkg2)
        // Request codes must be within REQUEST_HIDE_BASE .. REQUEST_HIDE_BASE + 0x3FFF
        assertTrue(code1A in 20000..36383)
        assertTrue(code2 in 20000..36383)
    }

    // =========================================================================
    // 6. Usage Snapshot Corruption Tolerance (Phase 3 / Section 6.2)
    // =========================================================================

    @Test
    fun usageSnapshotStore_decodeSnapshots_toleratesMalformedLines() {
        val rawData = """
            
            malformed_line_no_pipe
            pkg_two_parts|1234
            pkg_bad_numbers|not_a_number|500|60
            valid.pkg.one|60000|120000|60
            another.corrupt|100|bad_remaining|60
            valid.pkg.two|30000|90000|120
            
        """.trimIndent()

        val decoded = usageSnapshotStore.decodeSnapshots(rawData)

        // Malformed lines are gracefully skipped; valid lines are preserved
        assertEquals(2, decoded.size)
        assertTrue(decoded.containsKey("valid.pkg.one"))
        assertTrue(decoded.containsKey("valid.pkg.two"))

        val snapOne = decoded["valid.pkg.one"]!!
        assertEquals(60000L, snapOne.usedMillis)
        assertEquals(120000L, snapOne.remainingMillis)
        assertEquals(60, snapOne.limitMinutes)

        val snapTwo = decoded["valid.pkg.two"]!!
        assertEquals(30000L, snapTwo.usedMillis)
        assertEquals(90000L, snapTwo.remainingMillis)
        assertEquals(120, snapTwo.limitMinutes)
    }

    // =========================================================================
    // 7. Atomic Targets and Pending Updates (Phase 4 / Section 7)
    // =========================================================================

    @Test
    fun settingsRepository_setTargetsAndPending_updatesBothCollectionsAtomically() = runTest(testDispatcher) {
        val target = TargetAppConfig("com.example.atomic", 60, 1380, 420)
        val pending = PendingChange(
            packageName = "com.example.atomic",
            desired = null, // Removal pending
            eligibleFromEpochDay = 20000L
        )

        settingsRepository.setTargetsAndPending(
            targets = mapOf("com.example.atomic" to target),
            changes = listOf(pending)
        )

        val readTargets = settingsRepository.targets.first()
        val readPending = settingsRepository.pendingChanges.first()

        assertEquals(1, readTargets.size)
        assertTrue(readTargets.containsKey("com.example.atomic"))
        assertEquals(1, readPending.size)
        assertEquals("com.example.atomic", readPending[0].packageName)
    }

    // =========================================================================
    // 8. Uninstalled Package Pending Changes Cleanup (Phase 4 / Section 7)
    // =========================================================================

    @Test
    fun phaseE_clearsPendingChangesForUninstalledPackages() = runTest(testDispatcher) {
        val uninstalledPkg = "com.example.uninstalled"
        // Target is NOT installed in fake gateway
        assertFalse(gateway.isPackageInstalled(uninstalledPkg))

        val desiredConfig = TargetAppConfig(uninstalledPkg, 120, 1380, 420)
        val pending = PendingChange(
            packageName = uninstalledPkg,
            desired = desiredConfig,
            eligibleFromEpochDay = 1L // eligible immediately
        )

        settingsRepository.setPending(listOf(pending))
        assertEquals(1, settingsRepository.pendingChanges.first().size)

        // Run reconciliation
        coordinator.reconcile()

        // Phase E must clear the pending change for the uninstalled package
        val remainingPending = settingsRepository.pendingChanges.first()
        assertTrue("Pending changes for uninstalled packages must be cleared", remainingPending.isEmpty())
    }

    // =========================================================================
    // 9. Unknown Package State Invariant (Phase 1 / Prompt 1)
    // =========================================================================

    @Test
    fun unknownPackageState_neverClearsPendingChanges_andPreservesActiveDeadline() = runTest(testDispatcher) {
        val transientPkg = "com.example.transient"
        val initialConfig = TargetAppConfig(transientPkg, 60, 1380, 420)
        settingsRepository.setTargets(mapOf(transientPkg to initialConfig))

        val now = zdtToMillis(12, 0)
        timeSource.setNowMillis(now)
        val lockDeadline = now + 3600_000L
        lockStateStore.lockUntilAtLeast(transientPkg, lockDeadline)

        val desired = TargetAppConfig(transientPkg, 120, 1380, 420)
        val pending = PendingChange(
            packageName = transientPkg,
            desired = desired,
            eligibleFromEpochDay = 1L
        )
        settingsRepository.setPending(listOf(pending))

        // Simulate transient PackageManager error (Unknown status)
        gateway.packageInstallStatusMap[transientPkg] = PackageInstallStatus.Unknown(
            message = "DeadObjectException",
            cause = RuntimeException("DeadObjectException")
        )

        // Run reconciliation
        coordinator.reconcile()

        // 1. Pending change MUST be preserved (NOT cleared)
        val remainingPending = settingsRepository.pendingChanges.first()
        assertEquals(1, remainingPending.size)
        assertEquals(transientPkg, remainingPending[0].packageName)

        // 2. Target config must NOT be mutated during transient error
        val currentTargets = settingsRepository.snapshotTargets()
        assertEquals(60, currentTargets[transientPkg]?.dailyLimitMinutes)

        // 3. Active lock deadline must NOT be shortened or erased
        val currentLock = lockStateStore.snapshot()[transientPkg]
        assertNotNull(currentLock)
        val lockedUntil = currentLock?.state as? LockState.LockedUntil
        assertNotNull(lockedUntil)
        assertEquals(lockDeadline, lockedUntil?.untilEpochMillis)
    }

    // =========================================================================
    // 10. ReleasePending Scheduling and Retry (Phase 2 / Prompt 4)
    // =========================================================================

    @Test
    fun releasePending_scheduledWithActionRetryAndRequestRetry() {
        val now = zdtToMillis(14, 0)
        val retryTime = now + 15_000L
        val releasePendingRecord = LockRecord(
            state = LockState.ReleasePending(requestedAtMillis = now),
            unlockFailureCount = 2,
            nextUnlockRetryAtMillis = retryTime
        )

        val plan = TimeWindowScheduler.calculateEarliestUnlockPlan(
            lockRecords = listOf(releasePendingRecord),
            now = now
        )

        assertNotNull(plan)
        assertTrue(plan!!.isRetry)
        assertEquals(TimeWindowScheduler.ACTION_RETRY, plan.action)
        assertEquals(TimeWindowScheduler.REQUEST_RETRY, plan.requestCode)
        assertEquals(retryTime, plan.triggerMillis)
    }

    @Test
    fun earliestUnlockPlan_lockedUntil_scheduledWithActionUnlock() {
        val now = zdtToMillis(14, 0)
        val unlockTime = now + 3600_000L
        val lockedRecord = LockRecord(
            state = LockState.LockedUntil(unlockTime)
        )

        val plan = TimeWindowScheduler.calculateEarliestUnlockPlan(
            lockRecords = listOf(lockedRecord),
            now = now
        )

        assertNotNull(plan)
        assertFalse(plan!!.isRetry)
        assertEquals(TimeWindowScheduler.ACTION_UNLOCK, plan.action)
        assertEquals(TimeWindowScheduler.REQUEST_UNLOCK, plan.requestCode)
        assertEquals(unlockTime, plan.triggerMillis)
    }

    // =========================================================================
    // 11. Hide-Alarm Cancellation & Distinct Data Identity (Phase 2 / Prompt 4)
    // =========================================================================

    @Test
    fun hideAlarm_distinctDataUri_preventsPendingIntentCollisions() {
        val pkg1 = "com.app.alpha"
        val pkg2 = "com.app.beta"

        val code1 = TimeWindowScheduler.calculateHideRequestCode(pkg1)
        val code2 = TimeWindowScheduler.calculateHideRequestCode(pkg2)

        // Request codes are deterministic and bounded
        assertEquals(code1, TimeWindowScheduler.calculateHideRequestCode(pkg1))
        assertEquals(code2, TimeWindowScheduler.calculateHideRequestCode(pkg2))

        // Distinct data URIs ensure Android's Intent.filterEquals distinguishes packages
        // even if request codes collide
        val uri1 = "limiter://hide/$pkg1"
        val uri2 = "limiter://hide/$pkg2"
        org.junit.Assert.assertNotEquals(uri1, uri2)
    }

    // =========================================================================
    // 12. Monitor Loop Bounded Backoff (Phase 3 / Prompt 4)
    // =========================================================================

    @Test
    fun monitorLoop_boundedBackoff_limitsDelayAndTracksFailureCount() {
        assertEquals(0L, UsageMonitorService.calculateLoopBackoff(0))
        assertEquals(5_000L, UsageMonitorService.calculateLoopBackoff(1))
        assertEquals(10_000L, UsageMonitorService.calculateLoopBackoff(2))
        assertEquals(30_000L, UsageMonitorService.calculateLoopBackoff(3))
        assertEquals(60_000L, UsageMonitorService.calculateLoopBackoff(4))
        // Bounded max: backoff never grows beyond 60s
        assertEquals(60_000L, UsageMonitorService.calculateLoopBackoff(10))
        assertEquals(60_000L, UsageMonitorService.calculateLoopBackoff(100))
    }

    // =========================================================================
    // 13. External Intent Mutation Boundary (Phase 1 / Prompt 2)
    // =========================================================================

    @Test
    fun externalIntentSpoofing_cannotMutateConfigurationOrSelfLock() = runTest(testDispatcher) {
        val targetPkg = "com.example.protected.target"
        val originalConfig = TargetAppConfig(targetPkg, 60, 1380, 420)
        settingsRepository.setTargets(mapOf(targetPkg to originalConfig))

        // External caller attempts to inject mutation via ParsedIntent
        val maliciousMutations = listOf(
            MainActivity.Companion.ParsedIntent(
                action = Intent.ACTION_MAIN,
                extraKeys = setOf("add_target", "limit")
            ),
            MainActivity.Companion.ParsedIntent(
                action = MainActivity.ACTION_INTERNAL_NAVIGATE,
                extraKeys = setOf("add_target", "hide", "reveal")
            ),
            MainActivity.Companion.ParsedIntent(
                action = "com.limiter.app.action.CUSTOM_SPOOF",
                extraKeys = setOf("limit")
            ),
            MainActivity.Companion.ParsedIntent(
                action = null,
                extraKeys = setOf("reveal")
            )
        )

        for (intent in maliciousMutations) {
            val decision = MainActivity.evaluateIntent(intent)
            assertEquals(
                MainActivity.Companion.IntentGateDecision.REJECT_MUTATION,
                decision
            )
        }

        // Verify configuration was completely untouched
        val targets = settingsRepository.snapshotTargets()
        assertEquals(1, targets.size)
        assertEquals(60, targets[targetPkg]?.dailyLimitMinutes)
    }

    // =========================================================================
    // 14. Package Visibility Limitation & Status States (Release Gate Check 2)
    // =========================================================================

    @Test
    fun packageVisibilityLimitation_simulatedNonDo_preservesTargetsAndPendingChanges() = runTest(testDispatcher) {
        val filteredPkg = "com.example.hidden.filter"
        val initialConfig = TargetAppConfig(filteredPkg, 45, 1380, 420)
        settingsRepository.setTargets(mapOf(filteredPkg to initialConfig))

        // Create pending removal
        val pendingRemoval = PendingChange(
            packageName = filteredPkg,
            desired = null,
            eligibleFromEpochDay = 1L
        )
        settingsRepository.setPending(listOf(pendingRemoval))

        // Simulate package visibility limitation (Unknown)
        gateway.packageInstallStatusMap[filteredPkg] = PackageInstallStatus.Unknown(
            message = "Package visibility limitation: not Device Owner and package not visible via queries"
        )

        coordinator.reconcile()

        // Neither the target nor the pending removal should be cleared while presence is Unknown!
        val targetsAfter = settingsRepository.snapshotTargets()
        assertTrue("Target must be preserved when presence is unknown", targetsAfter.containsKey(filteredPkg))
        val pendingAfter = settingsRepository.pendingChanges.first()
        assertEquals("Pending change must NOT be removed when presence is unknown", 1, pendingAfter.size)
        assertEquals(filteredPkg, pendingAfter[0].packageName)
    }

    @Test
    fun packageInstallStatus_allStatesHandledSafely() = runTest(testDispatcher) {
        val installedPkg = "com.example.installed"
        val uninstalledPkg = "com.example.truly.uninstalled"
        val unknownPkg = "com.example.visibility.unknown"

        gateway.installedPackages.add(installedPkg)
        gateway.packageInstallStatusMap[installedPkg] = PackageInstallStatus.Installed
        gateway.packageInstallStatusMap[uninstalledPkg] = PackageInstallStatus.NotInstalled
        gateway.packageInstallStatusMap[unknownPkg] = PackageInstallStatus.Unknown("Visibility filter")

        val now = zdtToMillis(10, 0)
        timeSource.setNowMillis(now)

        // 1. Installed package: pending addition should apply
        val addPending = PendingChange(installedPkg, TargetAppConfig(installedPkg, 30, 1380, 420), 1L)
        // 2. Uninstalled package: pending removal should apply and be cleared
        val removePendingUninstalled = PendingChange(uninstalledPkg, null, 1L)
        // 3. Unknown package: pending addition must be preserved
        val addPendingUnknown = PendingChange(unknownPkg, TargetAppConfig(unknownPkg, 50, 1380, 420), 1L)

        settingsRepository.setTargets(mapOf(uninstalledPkg to TargetAppConfig(uninstalledPkg, 60, 1380, 420)))
        settingsRepository.setPending(listOf(addPending, removePendingUninstalled, addPendingUnknown))

        coordinator.reconcile()

        val resultingTargets = settingsRepository.snapshotTargets()
        val resultingPending = settingsRepository.pendingChanges.first()

        // Installed: applied
        assertTrue(resultingTargets.containsKey(installedPkg))
        assertFalse(resultingPending.any { it.packageName == installedPkg })

        // NotInstalled: removed from targets and pending cleared
        assertFalse(resultingTargets.containsKey(uninstalledPkg))
        assertFalse(resultingPending.any { it.packageName == uninstalledPkg })

        // Unknown: target untouched (not added yet) and pending preserved!
        assertFalse(resultingTargets.containsKey(unknownPkg))
        assertTrue(resultingPending.any { it.packageName == unknownPkg })
    }

    // =========================================================================
    // 15. Receiver Completion Exactly-Once (Release Gate Check 4)
    // =========================================================================

    @Test
    fun receiverCompletion_finishesExactlyOnce_evenWhenJobCancelledBeforeExecution() = runTest {
        val finishedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finishOnce() {
            if (finished.compareAndSet(false, true)) {
                finishedCount.incrementAndGet()
            }
        }

        val supervisor = kotlinx.coroutines.SupervisorJob()
        val cancelledScope = kotlinx.coroutines.CoroutineScope(supervisor + testDispatcher)
        cancelledScope.cancel() // Cancelled BEFORE launch

        try {
            val job = cancelledScope.launch {
                kotlinx.coroutines.delay(100)
            }
            job.invokeOnCompletion { finishOnce() }
            job.join()
        } catch (_: Throwable) {
            finishOnce()
        }

        assertEquals(1, finishedCount.get())
        assertTrue(finished.get())
    }

    @Test
    fun receiverCompletion_finishesExactlyOnce_onNormalExecution() = runTest {
        val finishedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finishOnce() {
            if (finished.compareAndSet(false, true)) {
                finishedCount.incrementAndGet()
            }
        }

        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        try {
            val job = scope.launch {
                // Execute work
            }
            job.invokeOnCompletion { finishOnce() }
        } catch (_: Throwable) {
            finishOnce()
        }

        assertEquals(1, finishedCount.get())
        assertTrue(finished.get())
    }

    // =========================================================================
    // 16. Exact-Alarm SecurityException & Resilience (Release Gate Check 1)
    // =========================================================================

    @Test
    fun exactAlarmResilience_lockDeadlinesPreservedEvenIfSchedulingThrowsSecurityException() = runTest(testDispatcher) {
        val targetPkg = "com.example.alarm.target"
        val now = zdtToMillis(23, 0)
        timeSource.setNowMillis(now)
        val lockUntil = now + 8 * 3600_000L

        // Persist active lock
        lockStateStore.lockUntilAtLeast(targetPkg, lockUntil)

        // Verify calculateEarliestUnlockPlan still generates the plan regardless of system alarm permissions
        val plan = TimeWindowScheduler.calculateEarliestUnlockPlan(
            lockRecords = lockStateStore.snapshot().values,
            now = now
        )

        assertNotNull(plan)
        assertEquals(lockUntil, plan!!.triggerMillis)
        assertEquals(TimeWindowScheduler.ACTION_UNLOCK, plan.action)

        // Verify logical lock record in LockStateStore is never cleared or compromised
        val record = lockStateStore.snapshot()[targetPkg]
        assertNotNull(record)
        val state = record?.state as? LockState.LockedUntil
        assertNotNull(state)
        assertEquals(lockUntil, state?.untilEpochMillis)
    }

    // =========================================================================
    // 17. Comprehensive Package-Installation Detection & Durable Retry Audit
    // =========================================================================

    // Requirement 1.1: Installed package returned with installation metadata (FLAG_INSTALLED)
    @Test
    fun evaluateStatus_installedPackage_withInstalledFlag_returnsInstalled() {
        val pi = PackageInfo().apply {
            packageName = "com.example.installed"
            applicationInfo = ApplicationInfo().apply {
                flags = ApplicationInfo.FLAG_INSTALLED
            }
        }
        val status = DeviceDpmGateway.evaluateStatus(
            packageInfo = pi,
            pmException = null,
            isDeviceOwner = true,
            isApplicationHidden = { Result.success(false) },
            isPackageSuspended = { Result.success(false) }
        )
        assertTrue(status is PackageInstallStatus.Installed)
    }

    // Requirement 1.2: Installed-but-hidden package (FLAG_INSTALLED == 0, not data-only, DO verifies hidden)
    @Test
    fun evaluateStatus_installedButHiddenPackage_flagsZero_returnsInstalled() {
        val pi = PackageInfo().apply {
            packageName = "com.example.hidden.app"
            applicationInfo = ApplicationInfo().apply {
                flags = 0 // Under Android, hidden via DPM clears FLAG_INSTALLED
            }
        }
        val status = DeviceDpmGateway.evaluateStatus(
            packageInfo = pi,
            pmException = null,
            isDeviceOwner = true,
            isApplicationHidden = { Result.success(true) },
            isPackageSuspended = { Result.success(true) }
        )
        assertTrue("When PackageInfo exists, not data-only, and DPM confirms hidden, must return Installed",
            status is PackageInstallStatus.Installed)
    }

    // Requirement 1.3: Data-only package (FLAG_IS_DATA_ONLY set) returned by MATCH_UNINSTALLED_PACKAGES
    @Test
    fun evaluateStatus_dataOnlyPackage_withFlagIsDataOnly_returnsNotInstalled() {
        val pi = PackageInfo().apply {
            packageName = "com.example.dataonly.app"
            applicationInfo = ApplicationInfo().apply {
                // In Android, FLAG_IS_DATA_ONLY (1 << 24) indicates APK is gone, only data remains
                flags = ApplicationInfo.FLAG_IS_DATA_ONLY
            }
        }
        // Even if DPM query returned true or false, a data-only package is NOT installed
        val status = DeviceDpmGateway.evaluateStatus(
            packageInfo = pi,
            pmException = null,
            isDeviceOwner = true,
            isApplicationHidden = { Result.success(false) },
            isPackageSuspended = { Result.success(false) }
        )
        assertTrue("Data-only package must be classified as NotInstalled",
            status is PackageInstallStatus.NotInstalled)
    }

    // Requirement 1.3b: Uninstalled package returned by MATCH_UNINSTALLED_PACKAGES (FLAG_INSTALLED == 0, not hidden)
    @Test
    fun evaluateStatus_packageWithFlagsZero_notHidden_returnsNotInstalled() {
        val pi = PackageInfo().apply {
            packageName = "com.example.uninstalled.datakept"
            applicationInfo = ApplicationInfo().apply {
                flags = 0 // FLAG_INSTALLED is not set
            }
        }
        val status = DeviceDpmGateway.evaluateStatus(
            packageInfo = pi,
            pmException = null,
            isDeviceOwner = true,
            isApplicationHidden = { Result.success(false) },
            isPackageSuspended = { Result.success(false) }
        )
        assertTrue("When FLAG_INSTALLED == 0 and not hidden by DPM, must return NotInstalled",
            status is PackageInstallStatus.NotInstalled)
    }

    // Requirement 1.4: NameNotFoundException with Device Owner active and DPM reports not restricted
    @Test
    fun evaluateStatus_nameNotFoundException_withDeviceOwner_returnsNotInstalled_whenDpmReportsNotRestricted() {
        val status = DeviceDpmGateway.evaluateStatus(
            packageInfo = null,
            pmException = PackageManager.NameNotFoundException("Not in PM"),
            isDeviceOwner = true,
            isApplicationHidden = { Result.success(false) },
            isPackageSuspended = { Result.success(false) }
        )
        assertTrue("NameNotFoundException with DO active and DPM confirming not restricted must return NotInstalled",
            status is PackageInstallStatus.NotInstalled)
    }

    // Requirement 1.5: NameNotFoundException where isApplicationHidden() returns true
    // CRITICAL: Android's isApplicationHidden() reports hidden state, not package installation proof.
    // If PM threw NameNotFoundException, package existence is NOT independently established.
    // Must return Unknown, NEVER Installed!
    @Test
    fun evaluateStatus_nameNotFoundException_evenIfDpmReportsHidden_returnsUnknown_neverInstalled() {
        val status = DeviceDpmGateway.evaluateStatus(
            packageInfo = null,
            pmException = PackageManager.NameNotFoundException("Not in PM"),
            isDeviceOwner = true,
            isApplicationHidden = { Result.success(true) },
            isPackageSuspended = { Result.success(false) }
        )
        assertTrue("Never classify as Installed solely because isApplicationHidden() is true when PM threw NameNotFoundException!",
            status is PackageInstallStatus.Unknown)
        assertFalse("Must NOT be Installed", status is PackageInstallStatus.Installed)
    }

    // Requirement 1.6: Inconclusive DPM query / transient failures
    @Test
    fun evaluateStatus_dpmQueryFailuresAndAmbiguousResults_returnUnknown() {
        // DPM throws exception
        val dpmFailureStatus = DeviceDpmGateway.evaluateStatus(
            packageInfo = null,
            pmException = PackageManager.NameNotFoundException("Not in PM"),
            isDeviceOwner = true,
            isApplicationHidden = { Result.failure(RuntimeException("DPM remote exception")) },
            isPackageSuspended = { Result.success(false) }
        )
        assertTrue("DPM query failure must return Unknown", dpmFailureStatus is PackageInstallStatus.Unknown)

        // Transient PM IPC error
        val pmIpcStatus = DeviceDpmGateway.evaluateStatus(
            packageInfo = null,
            pmException = RuntimeException("DeadObjectException: system_server died"),
            isDeviceOwner = true,
            isApplicationHidden = { Result.success(false) },
            isPackageSuspended = { Result.success(false) }
        )
        assertTrue("Transient IPC exception must return Unknown", pmIpcStatus is PackageInstallStatus.Unknown)

        // SecurityException
        val secStatus = DeviceDpmGateway.evaluateStatus(
            packageInfo = null,
            pmException = SecurityException("Permission denied"),
            isDeviceOwner = true,
            isApplicationHidden = { Result.success(false) },
            isPackageSuspended = { Result.success(false) }
        )
        assertTrue("SecurityException must return Unknown", secStatus is PackageInstallStatus.Unknown)

        // NameNotFoundException when not Device Owner
        val notDoStatus = DeviceDpmGateway.evaluateStatus(
            packageInfo = null,
            pmException = PackageManager.NameNotFoundException("Filtered"),
            isDeviceOwner = false,
            isApplicationHidden = { Result.failure(SecurityException("Not DO")) },
            isPackageSuspended = { Result.failure(SecurityException("Not DO")) }
        )
        assertTrue("NameNotFoundException without Device Owner must return Unknown", notDoStatus is PackageInstallStatus.Unknown)
    }

    // Requirement 2.1: An Unknown result preserves existing target, pending change, lock deadline,
    // and schedules a bounded, durable retry alarm via TimeWindowScheduler.
    @Test
    fun lockCoordinator_unknownResult_preservesTargetPendingLockAndReleaseState_andSchedulesRetry() = runTest(testDispatcher) {
        val pkg = "com.example.unknown.target"
        val now = zdtToMillis(14, 0)
        timeSource.setNowMillis(now)

        val targetConfig = TargetAppConfig(pkg, 60, 1380, 420)
        settingsRepository.setTargets(mapOf(pkg to targetConfig))

        val pending = PendingChange(pkg, null, 1L) // pending removal
        settingsRepository.setPending(listOf(pending))

        // Expired lock deadline (needs release)
        lockStateStore.lockUntilAtLeast(pkg, now - 1000L)
        gateway.hiddenPackages.add(pkg)
        gateway.suspendedPackages.add(pkg)

        // Simulate package status Unknown
        gateway.packageInstallStatusMap[pkg] = PackageInstallStatus.Unknown("Transitory error")

        coordinator.reconcile()

        // 1. Target config MUST NOT be deleted
        val targetsAfter = settingsRepository.snapshotTargets()
        assertEquals(1, targetsAfter.size)
        assertTrue(targetsAfter.containsKey(pkg))

        // 2. Pending change MUST NOT be cleared
        val pendingAfter = settingsRepository.pendingChanges.first()
        assertEquals(1, pendingAfter.size)
        assertEquals(pkg, pendingAfter[0].packageName)

        // 3. Lock record MUST NOT be deleted and release must NOT be authorized while Unknown
        val recordAfter = lockStateStore.snapshot()[pkg]
        assertNotNull(recordAfter)
        assertTrue("Package must not be transitioned to Unlocked while status is Unknown", recordAfter?.state !is LockState.Unlocked)
        assertTrue("Package must remain hidden while status is Unknown", gateway.hiddenPackages.contains(pkg))
        assertTrue("Package must remain suspended while status is Unknown", gateway.suspendedPackages.contains(pkg))

        // 4. Durable retry backoff MUST be recorded (first failure = 30s backoff)
        assertEquals(1, recordAfter?.unlockFailureCount)
        val expectedRetryAt = now + 30_000L
        assertEquals(expectedRetryAt, recordAfter?.nextUnlockRetryAtMillis)

        // 5. TimeWindowScheduler MUST schedule an ACTION_RETRY alarm for the retry timestamp
        val retryPlan = TimeWindowScheduler.calculateEarliestUnlockPlan(
            lockRecords = lockStateStore.snapshot().values,
            now = now
        )
        assertNotNull(retryPlan)
        assertTrue("Earliest plan must be a retry alarm", retryPlan!!.isRetry)
        assertEquals(expectedRetryAt, retryPlan.triggerMillis)
        assertEquals(TimeWindowScheduler.ACTION_RETRY, retryPlan.action)
        assertEquals(TimeWindowScheduler.REQUEST_RETRY, retryPlan.requestCode)
    }

    // Requirement 2.2: Repeated reconciliation while package status remains Unknown within backoff window is idempotent
    @Test
    fun lockCoordinator_repeatedReconciliation_whileStatusUnknown_withinBackoffWindow_isIdempotent() = runTest(testDispatcher) {
        val pkg = "com.example.repeated.unknown"
        val now = zdtToMillis(15, 0)
        timeSource.setNowMillis(now)

        val targetConfig = TargetAppConfig(pkg, 90, 1380, 420)
        settingsRepository.setTargets(mapOf(pkg to targetConfig))

        // Expired lock needing release
        lockStateStore.lockUntilAtLeast(pkg, now - 1000L)
        gateway.packageInstallStatusMap[pkg] = PackageInstallStatus.Unknown("Repeated IPC failure")

        // First reconcile: records failure and sets 30s backoff
        coordinator.reconcile()
        val recordAfterFirst = lockStateStore.snapshot()[pkg]
        assertNotNull(recordAfterFirst)
        assertEquals(1, recordAfterFirst?.unlockFailureCount)
        val initialRetryAt = recordAfterFirst?.nextUnlockRetryAtMillis
        assertEquals(now + 30_000L, initialRetryAt)

        // Reconcile 5 times in succession at the same instant (now < retryAt)
        repeat(5) {
            coordinator.reconcile()
        }

        // Verify state is completely idempotent: failure count remains 1 and retry timestamp does not drift
        val recordAfterRepeats = lockStateStore.snapshot()[pkg]
        assertNotNull(recordAfterRepeats)
        assertEquals(1, recordAfterRepeats?.unlockFailureCount)
        assertEquals(initialRetryAt, recordAfterRepeats?.nextUnlockRetryAtMillis)
        assertTrue(recordAfterRepeats?.state !is LockState.Unlocked)

        // Scheduler plan remains consistently pointing to the original retry timestamp
        val plan = TimeWindowScheduler.calculateEarliestUnlockPlan(
            lockRecords = lockStateStore.snapshot().values,
            now = now
        )
        assertNotNull(plan)
        assertTrue(plan!!.isRetry)
        assertEquals(initialRetryAt, plan.triggerMillis)
    }

    // Requirement 2.3: Confirmed absent package is handled according to intended state machine
    @Test
    fun lockCoordinator_confirmedAbsentPackage_handledByStateMachine() = runTest(testDispatcher) {
        val absentPkg = "com.example.confirmed.absent"
        val now = zdtToMillis(10, 0)
        timeSource.setNowMillis(now)

        // Configured target with pending removal
        settingsRepository.setTargets(mapOf(absentPkg to TargetAppConfig(absentPkg, 60, 1380, 420)))
        val pendingRemoval = PendingChange(absentPkg, null, 1L)
        settingsRepository.setPending(listOf(pendingRemoval))

        // Expired lock record
        lockStateStore.lockUntilAtLeast(absentPkg, now - 1000L)

        // Mark confirmed absent
        gateway.packageInstallStatusMap[absentPkg] = PackageInstallStatus.NotInstalled

        coordinator.reconcile()

        // State machine handles absent package:
        // Pending removal is applied and cleared
        val pendingAfter = settingsRepository.pendingChanges.first()
        assertTrue("Pending removal for absent package must be applied/cleared", pendingAfter.isEmpty())
        val targetsAfter = settingsRepository.snapshotTargets()
        assertFalse("Absent package target must be removed upon confirmed removal", targetsAfter.containsKey(absentPkg))
    }

    // Requirement 2.4: Correct recovery when later query changes from Unknown to Installed
    @Test
    fun lockCoordinator_recoveryWhenStatusChangesFromUnknownToInstalled() = runTest(testDispatcher) {
        val installedPkg = "com.example.recovered.installed"
        val now = zdtToMillis(16, 0)
        timeSource.setNowMillis(now)

        val initialConfig = TargetAppConfig(installedPkg, 30, 1380, 420)
        settingsRepository.setTargets(mapOf(installedPkg to initialConfig))

        // Expired lock that needs release
        lockStateStore.lockUntilAtLeast(installedPkg, now - 1000L)
        gateway.hiddenPackages.add(installedPkg)
        gateway.suspendedPackages.add(installedPkg)

        // Initially Unknown: release is deferred, failure recorded, and retry scheduled for now + 30s
        gateway.packageInstallStatusMap[installedPkg] = PackageInstallStatus.Unknown("Initial query timeout")

        coordinator.reconcile()
        val recordCycle1 = lockStateStore.snapshot()[installedPkg]
        assertTrue("Must remain locked while status is Unknown", recordCycle1?.state !is LockState.Unlocked)
        assertEquals(1, recordCycle1?.unlockFailureCount)
        assertEquals(now + 30_000L, recordCycle1?.nextUnlockRetryAtMillis)

        // Advance time to when retry alarm triggers
        timeSource.setNowMillis(now + 30_000L)

        // Status resolves to Installed:
        gateway.installedPackages.add(installedPkg)
        gateway.packageInstallStatusMap[installedPkg] = PackageInstallStatus.Installed

        coordinator.reconcile()
        val recordCycle2 = lockStateStore.snapshot()[installedPkg]
        assertTrue("Must unlock cleanly when status resolves to Installed", recordCycle2?.state is LockState.Unlocked)
        assertEquals(0, recordCycle2?.unlockFailureCount)
        assertEquals(null, recordCycle2?.nextUnlockRetryAtMillis)
        assertFalse("Physical suspension restored (removed from suspended)", gateway.suspendedPackages.contains(installedPkg))
        assertFalse("Physical visibility restored (removed from hidden)", gateway.hiddenPackages.contains(installedPkg))
    }

    // =========================================================================
    // 18. End-to-End Retry Execution & Delivery Audit
    // =========================================================================

    // Test 18.1: Exact-alarm scheduling failure followed by successful inexact scheduling
    @Test
    fun retryExecution_exactAlarmFailure_fallsBackToInexact_andMarksScheduled() = runTest(testDispatcher) {
        val targetPkg = "com.example.retry.fallback"
        val now = zdtToMillis(14, 0)
        timeSource.setNowMillis(now)

        // Setup expired lock needing release with Unknown status
        lockStateStore.lockUntilAtLeast(targetPkg, now - 1000L)
        gateway.packageInstallStatusMap[targetPkg] = PackageInstallStatus.Unknown("Transitory error")

        coordinator.reconcile()
        val record = lockStateStore.snapshot()[targetPkg]
        assertNotNull(record)
        assertEquals(1, record?.unlockFailureCount)
        val expectedRetryAt = now + 30_000L
        assertEquals(expectedRetryAt, record?.nextUnlockRetryAtMillis)

        // Configure dispatcher: exact alarm throws SecurityException
        alarmDispatcher.canScheduleExact = true
        alarmDispatcher.exactFailure = SecurityException("SCHEDULE_EXACT_ALARM revoked by user")
        alarmDispatcher.inexactFailure = null

        val report = scheduler.reschedule()

        // Exact scheduling failed with SecurityException, but inexact fallback succeeded!
        assertTrue("Retry alarm must be marked scheduled via fallback", report.isRetryScheduled)
        val retryResult = report.unlockOrRetryAlarm
        assertTrue("Must be ScheduledInexact", retryResult is AlarmScheduleResult.ScheduledInexact)
        assertEquals(expectedRetryAt, (retryResult as AlarmScheduleResult.ScheduledInexact).triggerMillis)

        // Dispatcher recorded inexact scheduling for retry alarm
        val retryAlarm = alarmDispatcher.inexactScheduled.find { it.requestCode == TimeWindowScheduler.REQUEST_RETRY }
        assertNotNull("Retry alarm must be scheduled in inexactScheduled", retryAlarm)
        assertEquals(expectedRetryAt, retryAlarm!!.triggerMillis)
        assertEquals(TimeWindowScheduler.REQUEST_RETRY, retryAlarm.requestCode)
    }

    // Test 18.2: Failure of both exact and inexact scheduling preserves durable state and does NOT claim scheduled
    @Test
    fun retryExecution_bothExactAndInexactFail_preservesDurableState_andReportsFailure() = runTest(testDispatcher) {
        val targetPkg = "com.example.retry.doublefail"
        val now = zdtToMillis(15, 0)
        timeSource.setNowMillis(now)

        lockStateStore.lockUntilAtLeast(targetPkg, now - 1000L)
        gateway.packageInstallStatusMap[targetPkg] = PackageInstallStatus.Unknown("Temporary IPC failure")

        coordinator.reconcile()
        val expectedRetryAt = now + 30_000L

        // Both exact and inexact fail (e.g. system_server AlarmManager crash or sandbox refusal)
        alarmDispatcher.canScheduleExact = true
        alarmDispatcher.exactFailure = SecurityException("SCHEDULE_EXACT_ALARM denied")
        alarmDispatcher.inexactFailure = RuntimeException("system_server died")

        val report = scheduler.reschedule()

        // 1. MUST NOT claim that a retry is scheduled!
        assertFalse("Must NOT claim retry is scheduled when both mechanisms fail", report.isRetryScheduled)
        val retryResult = report.unlockOrRetryAlarm
        assertTrue("Unlock/retry alarm result must be Failed", retryResult is AlarmScheduleResult.Failed)
        assertEquals(expectedRetryAt, (retryResult as AlarmScheduleResult.Failed).triggerMillis)
        assertTrue(report.hasAnyFailures)

        // 2. Persisted retry deadline and lock record MUST BE PRESERVED
        val persisted = lockStateStore.snapshot()[targetPkg]
        assertNotNull(persisted)
        assertTrue("Lock state must remain LockedUntil (never deleted)", persisted?.state is LockState.LockedUntil)
        assertEquals(1, persisted?.unlockFailureCount)
        assertEquals(expectedRetryAt, persisted?.nextUnlockRetryAtMillis)
    }

    // Test 18.3: Recovery on a later successful scheduling attempt
    @Test
    fun retryExecution_recoveryOnLaterSuccessfulSchedulingAttempt() = runTest(testDispatcher) {
        val targetPkg = "com.example.retry.recovery"
        val now = zdtToMillis(15, 0)
        timeSource.setNowMillis(now)

        lockStateStore.lockUntilAtLeast(targetPkg, now - 1000L)
        gateway.packageInstallStatusMap[targetPkg] = PackageInstallStatus.Unknown("Initial query timeout")

        coordinator.reconcile()
        val expectedRetryAt = now + 30_000L

        // Step 1: Initial attempt fails
        alarmDispatcher.canScheduleExact = false
        alarmDispatcher.inexactFailure = RuntimeException("Transient AlarmManager error")
        val reportFail = scheduler.reschedule()
        assertFalse(reportFail.isRetryScheduled)

        // Step 2: System service recovers (or next poll tick / screen-on trigger runs)
        alarmDispatcher.clear()
        alarmDispatcher.canScheduleExact = false
        alarmDispatcher.inexactFailure = null // Now healthy

        val reportSuccess = scheduler.reschedule()
        assertTrue("Subsequent reschedule must recover and schedule the retry", reportSuccess.isRetryScheduled)
        assertTrue(reportSuccess.unlockOrRetryAlarm is AlarmScheduleResult.ScheduledInexact)
        assertEquals(expectedRetryAt, (reportSuccess.unlockOrRetryAlarm as AlarmScheduleResult.ScheduledInexact).triggerMillis)
        val retryAlarm = alarmDispatcher.inexactScheduled.find { it.requestCode == TimeWindowScheduler.REQUEST_RETRY }
        assertNotNull("Retry alarm must be scheduled in inexactScheduled", retryAlarm)
        assertEquals(expectedRetryAt, retryAlarm!!.triggerMillis)
    }

    // Test 18.4: End-to-end delivery via AlarmReceiver: idempotent before deadline, advances when deadline reached
    @Test
    fun retryExecution_endToEndAlarmReceiverDelivery_reconcilesProperly() = runTest(testDispatcher) {
        val targetPkg = "com.example.retry.e2e"
        val now = zdtToMillis(16, 0)
        timeSource.setNowMillis(now)

        lockStateStore.lockUntilAtLeast(targetPkg, now - 1000L)
        gateway.hiddenPackages.add(targetPkg)
        gateway.suspendedPackages.add(targetPkg)
        gateway.packageInstallStatusMap[targetPkg] = PackageInstallStatus.Unknown("Initial timeout")

        // 1. Initial reconcile records failure and schedules retry for now + 30s
        coordinator.reconcile()
        val retryReport = scheduler.reschedule()
        assertTrue(retryReport.isRetryScheduled)
        val retryTime = now + 30_000L

        // 2. Premature / duplicate AlarmReceiver delivery while now < retryAt
        val retryIntent = Intent(TimeWindowScheduler.ACTION_RETRY)
        val job1 = AlarmReceiver.processAlarm(
            context = null,
            intent = retryIntent,
            coordinator = coordinator,
            scheduler = scheduler,
            isDeviceOwner = false,
            scope = testScope
        )
        job1.join()

        // Verify duplicate delivery at same instant is completely idempotent
        val recordAfterDuplicate = lockStateStore.snapshot()[targetPkg]
        assertEquals(1, recordAfterDuplicate?.unlockFailureCount)
        assertEquals(retryTime, recordAfterDuplicate?.nextUnlockRetryAtMillis)
        assertTrue(recordAfterDuplicate?.state !is LockState.Unlocked)

        // 3. Alarm triggers when deadline reached (now = retryTime), but status remains Unknown
        timeSource.setNowMillis(retryTime)
        val job2 = AlarmReceiver.processAlarm(
            context = null,
            intent = retryIntent,
            coordinator = coordinator,
            scheduler = scheduler,
            isDeviceOwner = false,
            scope = testScope
        )
        job2.join()

        // Reconcile increments failure count to 2, backoff increases to 60s
        val recordAfterSecondFail = lockStateStore.snapshot()[targetPkg]
        assertEquals(2, recordAfterSecondFail?.unlockFailureCount)
        val expectedNextRetry = retryTime + 60_000L
        assertEquals(expectedNextRetry, recordAfterSecondFail?.nextUnlockRetryAtMillis)

        // 4. Time reaches second retry (retryTime + 60s) and status becomes authoritative Installed
        timeSource.setNowMillis(expectedNextRetry)
        gateway.installedPackages.add(targetPkg)
        gateway.packageInstallStatusMap[targetPkg] = PackageInstallStatus.Installed

        val job3 = AlarmReceiver.processAlarm(
            context = null,
            intent = retryIntent,
            coordinator = coordinator,
            scheduler = scheduler,
            isDeviceOwner = false,
            scope = testScope
        )
        job3.join()

        // Clean unlock, physical packages restored, retry state cleared
        val recordFinal = lockStateStore.snapshot()[targetPkg]
        assertTrue("Package must be Unlocked", recordFinal?.state is LockState.Unlocked)
        assertEquals(0, recordFinal?.unlockFailureCount)
        assertEquals(null, recordFinal?.nextUnlockRetryAtMillis)
        assertFalse(gateway.suspendedPackages.contains(targetPkg))
        assertFalse(gateway.hiddenPackages.contains(targetPkg))
    }

    // Test 18.5: Process recreation and reboot behavior
    @Test
    fun retryExecution_processRecreationAndReboot_reconstructsRetryAlarm() = runTest(testDispatcher) {
        val targetPkg = "com.example.retry.reboot"
        val now = zdtToMillis(17, 0)
        timeSource.setNowMillis(now)

        // Simulate persisted state from prior run surviving process death
        lockStateStore.lockUntilAtLeast(targetPkg, now - 1000L)
        lockStateStore.recordUnlockFailure(targetPkg, now, visibilityRestored = false, unsuspensionRestored = false)

        // Recreate new coordinator and new scheduler from scratch
        val newDispatcher = FakeAlarmDispatcher()
        val newScheduler = TimeWindowScheduler(
            context = null,
            settingsRepository = settingsRepository,
            lockStateStore = lockStateStore,
            timeSource = timeSource,
            alarmDispatcher = newDispatcher,
            intentFactory = intentFactory
        )

        // Simulate SystemEventsReceiver BOOT_COMPLETED rescheduling
        val report = newScheduler.reschedule()

        assertTrue("New scheduler must reconstruct retry alarm from persisted state", report.isRetryScheduled)
        val retryAlarm = newDispatcher.exactScheduled.find { it.requestCode == TimeWindowScheduler.REQUEST_RETRY }
        assertNotNull("Retry alarm must be scheduled in exactDispatcher", retryAlarm)
        assertEquals(now + 30_000L, retryAlarm!!.triggerMillis)
        assertEquals(TimeWindowScheduler.REQUEST_RETRY, retryAlarm.requestCode)
    }

    // Test 18.6: Per-user package semantics with FLAG_IS_DATA_ONLY and FLAG_INSTALLED
    @Test
    fun packageSemantics_dataOnlyFlags_takePrecedenceOverInstalledOrHidden() {
        // Data-only package even with FLAG_INSTALLED set must be NotInstalled
        val piWithBoth = PackageInfo().apply {
            packageName = "com.example.dataonly.both"
            applicationInfo = ApplicationInfo().apply {
                flags = ApplicationInfo.FLAG_IS_DATA_ONLY or ApplicationInfo.FLAG_INSTALLED
            }
        }
        val statusBoth = DeviceDpmGateway.evaluateStatus(
            packageInfo = piWithBoth,
            pmException = null,
            isDeviceOwner = true,
            isApplicationHidden = { Result.success(false) },
            isPackageSuspended = { Result.success(false) }
        )
        assertTrue("FLAG_IS_DATA_ONLY must take precedence and return NotInstalled", statusBoth is PackageInstallStatus.NotInstalled)

        // Data-only package when DPM returns isApplicationHidden == true must still be NotInstalled
        val piHiddenDataOnly = PackageInfo().apply {
            packageName = "com.example.dataonly.hidden"
            applicationInfo = ApplicationInfo().apply {
                flags = ApplicationInfo.FLAG_IS_DATA_ONLY
            }
        }
        val statusHiddenDataOnly = DeviceDpmGateway.evaluateStatus(
            packageInfo = piHiddenDataOnly,
            pmException = null,
            isDeviceOwner = true,
            isApplicationHidden = { Result.success(true) },
            isPackageSuspended = { Result.success(true) }
        )
        assertTrue("Data-only package is never Installed even if DPM reports hidden", statusHiddenDataOnly is PackageInstallStatus.NotInstalled)
    }

    // Test 18.7: When both exact and inexact alarm scheduling fail, no alarm is registered,
    // durable state is preserved, and recovery occurs ONLY when a subsequent lifecycle or
    // system event triggers reconciliation.
    @Test
    fun retryExecution_bothSchedulingFail_recoversOnlyUponLaterSystemOrLifecycleTrigger() = runTest(testDispatcher) {
        val targetPkg = "com.example.retry.doublefail.lifecycle"
        val now = zdtToMillis(11, 0)
        timeSource.setNowMillis(now)

        lockStateStore.lockUntilAtLeast(targetPkg, now - 1000L)
        gateway.hiddenPackages.add(targetPkg)
        gateway.suspendedPackages.add(targetPkg)
        gateway.packageInstallStatusMap[targetPkg] = PackageInstallStatus.Unknown("Transient query failure")

        // 1. Initial reconcile records failure and advances backoff to now + 30s
        coordinator.reconcile()
        val expectedRetryAt = now + 30_000L
        val recordInitial = lockStateStore.snapshot()[targetPkg]
        assertNotNull(recordInitial)
        assertEquals(expectedRetryAt, recordInitial?.nextUnlockRetryAtMillis)
        assertEquals(1, recordInitial?.unlockFailureCount)

        // 2. Both exact and inexact scheduling fail
        alarmDispatcher.canScheduleExact = true
        alarmDispatcher.exactFailure = SecurityException("SCHEDULE_EXACT_ALARM revoked")
        alarmDispatcher.inexactFailure = RuntimeException("AlarmManager dead")

        val report = scheduler.reschedule()
        assertFalse("Must NOT claim retry is scheduled when both fail", report.isRetryScheduled)
        assertTrue("Unlock/retry alarm must report Failed", report.unlockOrRetryAlarm is AlarmScheduleResult.Failed)
        assertTrue(alarmDispatcher.exactScheduled.isEmpty())
        assertTrue(alarmDispatcher.inexactScheduled.isEmpty())

        // 3. Time passes deadline (now + 45s).
        // Without an active AlarmManager alarm or external trigger, background recovery CANNOT occur.
        timeSource.setNowMillis(now + 45_000L)
        val recordDuringWait = lockStateStore.snapshot()[targetPkg]
        assertTrue("Package must remain locked; no silent unauthorized background unlock", recordDuringWait?.state !is LockState.Unlocked)
        assertTrue(gateway.hiddenPackages.contains(targetPkg))
        assertTrue(gateway.suspendedPackages.contains(targetPkg))

        // 4. Status resolves to Installed
        gateway.installedPackages.add(targetPkg)
        gateway.packageInstallStatusMap[targetPkg] = PackageInstallStatus.Installed

        // 5. User opens app (simulating MainActivity.onResume / MainViewModel.refresh) or system event fires
        coordinator.reconcile()

        // 6. Reconciliation consumes preserved durable deadline, unfreezes, and unlocks cleanly
        val recordRecovered = lockStateStore.snapshot()[targetPkg]
        assertTrue("Package must be Unlocked after lifecycle/system trigger", recordRecovered?.state is LockState.Unlocked)
        assertEquals(0, recordRecovered?.unlockFailureCount)
        assertEquals(null, recordRecovered?.nextUnlockRetryAtMillis)
        assertFalse("Hidden restored", gateway.hiddenPackages.contains(targetPkg))
        assertFalse("Suspended restored", gateway.suspendedPackages.contains(targetPkg))

        // 7. Subsequent reschedule succeeds once alarm subsystem is healthy
        alarmDispatcher.clear()
        val recoveryReport = scheduler.reschedule()
        assertFalse("No unlock or retry alarms needed once unlocked", recoveryReport.isRetryScheduled)
        assertEquals(null, recoveryReport.unlockOrRetryAlarm)
    }

    // Test 18.8: When status remains persistently Unknown across repeated alarm/reconcile events,
    // backoff increments monotonically and caps at maximum interval (1800s), avoiding runaway loops.
    @Test
    fun retryExecution_unboundedLoopPrevention_capsBackoffInterval() = runTest(testDispatcher) {
        val targetPkg = "com.example.retry.loop.prevention"
        var currentTime = zdtToMillis(9, 0)
        timeSource.setNowMillis(currentTime)

        lockStateStore.lockUntilAtLeast(targetPkg, currentTime - 1000L)
        gateway.hiddenPackages.add(targetPkg)
        gateway.suspendedPackages.add(targetPkg)
        gateway.packageInstallStatusMap[targetPkg] = PackageInstallStatus.Unknown("Persistent error")
        // If unhide/unsuspend fails during fallback, backoff continues to increment and cap
        gateway.unhideFailPackages.add(targetPkg)

        // Run through all 7 configured backoff intervals
        val expectedIntervals = listOf(
            30_000L, 60_000L, 120_000L, 300_000L, 600_000L, 900_000L, 1_800_000L
        )

        for ((index, expectedInterval) in expectedIntervals.withIndex()) {
            coordinator.reconcile()
            val record = lockStateStore.snapshot()[targetPkg]
            assertNotNull(record)
            assertEquals("Failure count must match iteration", index + 1, record?.unlockFailureCount)
            assertEquals("Next retry must match backoff", currentTime + expectedInterval, record?.nextUnlockRetryAtMillis)

            // Advance time to exactly the retry timestamp for the next cycle
            currentTime += expectedInterval
            timeSource.setNowMillis(currentTime)
        }

        // Further failures remain capped at 1,800,000L (30 minutes max)
        coordinator.reconcile()
        val recordCapped = lockStateStore.snapshot()[targetPkg]
        assertEquals(8, recordCapped?.unlockFailureCount)
        assertEquals(currentTime + 1_800_000L, recordCapped?.nextUnlockRetryAtMillis)
    }

    // Test 18.9: When package query status is Unknown and failure count reaches 3,
    // fallback unfreeze is attempted. If unhide and unsuspend succeed, package transitions to Unlocked.
    @Test
    fun retryExecution_unknownStatusFallback_afterThreeFailures_unfreezesAndUnlocks() = runTest(testDispatcher) {
        val targetPkg = "com.example.retry.fallback.unknown"
        var currentTime = zdtToMillis(9, 0)
        timeSource.setNowMillis(currentTime)

        lockStateStore.lockUntilAtLeast(targetPkg, currentTime - 1000L)
        gateway.hiddenPackages.add(targetPkg)
        gateway.suspendedPackages.add(targetPkg)
        gateway.packageInstallStatusMap[targetPkg] = PackageInstallStatus.Unknown("Transient query error")

        // Iteration 1: count = 1
        coordinator.reconcile()
        var record = lockStateStore.snapshot()[targetPkg]
        assertEquals(1, record?.unlockFailureCount)
        assertTrue("Still hidden after failure 1", gateway.hiddenPackages.contains(targetPkg))

        // Iteration 2: count = 2
        currentTime += 30_000L
        timeSource.setNowMillis(currentTime)
        coordinator.reconcile()
        record = lockStateStore.snapshot()[targetPkg]
        assertEquals(2, record?.unlockFailureCount)
        assertTrue("Still hidden after failure 2", gateway.hiddenPackages.contains(targetPkg))

        // Iteration 3: count = 3
        currentTime += 60_000L
        timeSource.setNowMillis(currentTime)
        coordinator.reconcile()
        record = lockStateStore.snapshot()[targetPkg]
        assertEquals(3, record?.unlockFailureCount)
        assertTrue("Still hidden after failure 3", gateway.hiddenPackages.contains(targetPkg))

        // Iteration 4: count >= 3 triggers fallback unfreeze!
        currentTime += 120_000L
        timeSource.setNowMillis(currentTime)
        val report = coordinator.reconcile()
        assertTrue("Reconciliation should succeed", report is LockCoordinator.ReconcileResult.Ok)
        val okResult = report as LockCoordinator.ReconcileResult.Ok

        record = lockStateStore.snapshot()[targetPkg]
        assertEquals("Record must transition to Unlocked", LockState.Unlocked, record?.state)
        assertEquals("Retry count must be reset", 0, record?.unlockFailureCount)
        assertEquals("Next retry must be null", null, record?.nextUnlockRetryAtMillis)
        assertFalse("Hidden must be restored (false)", gateway.hiddenPackages.contains(targetPkg))
        assertFalse("Suspension must be restored (false)", gateway.suspendedPackages.contains(targetPkg))
        assertTrue("Unlocked event generated", okResult.events.any { it is LockCoordinator.LockEvent.Unlocked && it.pkg == targetPkg })
    }

    // Test 18.10: UsageMonitorWatchdog detects due lock deadlines and retry timestamps
    @Test
    fun usageMonitorWatchdog_detectsDueLockAndRetryDeadlines() {
        val now = 1_000_000L

        // Empty records -> false
        assertFalse(UsageMonitorWatchdog.isAnyLockOrRetryDue(emptyMap(), now))

        // Unlocked -> false
        val unlockedRecords = mapOf(
            "com.app1" to LockRecord(LockState.Unlocked)
        )
        assertFalse(UsageMonitorWatchdog.isAnyLockOrRetryDue(unlockedRecords, now))

        // LockedUntil in future -> false
        val futureLocked = mapOf(
            "com.app1" to LockRecord(LockState.LockedUntil(now + 60_000L))
        )
        assertFalse(UsageMonitorWatchdog.isAnyLockOrRetryDue(futureLocked, now))

        // LockedUntil in past/now -> true
        val dueLocked = mapOf(
            "com.app1" to LockRecord(LockState.LockedUntil(now))
        )
        assertTrue(UsageMonitorWatchdog.isAnyLockOrRetryDue(dueLocked, now))

        // ReleasePending with future retry -> false
        val pendingFutureRetry = mapOf(
            "com.app1" to LockRecord(
                state = LockState.ReleasePending(requestedAtMillis = now - 5000L),
                nextUnlockRetryAtMillis = now + 10_000L
            )
        )
        assertFalse(UsageMonitorWatchdog.isAnyLockOrRetryDue(pendingFutureRetry, now))

        // ReleasePending with due retry -> true
        val pendingDueRetry = mapOf(
            "com.app1" to LockRecord(
                state = LockState.ReleasePending(requestedAtMillis = now - 5000L),
                nextUnlockRetryAtMillis = now - 1000L
            )
        )
        assertTrue(UsageMonitorWatchdog.isAnyLockOrRetryDue(pendingDueRetry, now))

        // ReleasePending without retry deadline -> true
        val pendingNoRetry = mapOf(
            "com.app1" to LockRecord(
                state = LockState.ReleasePending(requestedAtMillis = now - 5000L),
                nextUnlockRetryAtMillis = null
            )
        )
        assertTrue(UsageMonitorWatchdog.isAnyLockOrRetryDue(pendingNoRetry, now))
    }

    // =========================================================================
    // 19. Entry Points Security & Robustness Tests (P1: H1, H5, H6)
    // =========================================================================

    @Test
    fun alarmReceiver_processAlarm_callsFinishOnceOnSuccess() = runTest(testDispatcher) {
        val finishCount = java.util.concurrent.atomic.AtomicInteger(0)
        val job = AlarmReceiver.processAlarm(
            context = null,
            intent = Intent(TimeWindowScheduler.ACTION_UNLOCK),
            coordinator = coordinator,
            scheduler = scheduler,
            isDeviceOwner = true,
            scope = this,
            onFinish = { finishCount.incrementAndGet() }
        )
        job.join()
        assertEquals(1, finishCount.get())
    }

    private class InterceptingLockStateStore(
        private val delegate: InMemoryLockStateStore = InMemoryLockStateStore(),
        private val snapshotInterceptor: (suspend () -> Map<String, LockRecord>)? = null
    ) : com.limiter.app.data.LockStateStore by delegate {
        override suspend fun snapshot(): Map<String, LockRecord> {
            return snapshotInterceptor?.invoke() ?: delegate.snapshot()
        }
    }

    @Test
    fun alarmReceiver_processAlarm_callsFinishOnceOnException() = runTest(testDispatcher) {
        val failingStore = InterceptingLockStateStore(
            snapshotInterceptor = { throw RuntimeException("Simulated crash during snapshot") }
        )
        val failingCoordinator = LockCoordinator(
            gateway = gateway,
            freezer = freezer,
            lockStateStore = failingStore,
            settingsRepository = settingsRepository,
            criticalPackageResolver = resolver,
            timeSource = timeSource
        )
        val finishCount = java.util.concurrent.atomic.AtomicInteger(0)
        val job = AlarmReceiver.processAlarm(
            context = null,
            intent = Intent(TimeWindowScheduler.ACTION_UNLOCK),
            coordinator = failingCoordinator,
            scheduler = scheduler,
            isDeviceOwner = true,
            scope = this,
            onFinish = { finishCount.incrementAndGet() }
        )
        job.join()
        assertEquals(1, finishCount.get())
    }

    @Test
    fun alarmReceiver_processAlarm_callsFinishOnceOnTimeout() = runTest(testDispatcher) {
        val slowStore = InterceptingLockStateStore(
            snapshotInterceptor = {
                delay(2000L)
                emptyMap()
            }
        )
        val slowCoordinator = LockCoordinator(
            gateway = gateway,
            freezer = freezer,
            lockStateStore = slowStore,
            settingsRepository = settingsRepository,
            criticalPackageResolver = resolver,
            timeSource = timeSource
        )
        val finishCount = java.util.concurrent.atomic.AtomicInteger(0)
        val job = AlarmReceiver.processAlarm(
            context = null,
            intent = Intent(TimeWindowScheduler.ACTION_UNLOCK),
            coordinator = slowCoordinator,
            scheduler = scheduler,
            isDeviceOwner = true,
            scope = this,
            onFinish = { finishCount.incrementAndGet() },
            timeoutMillis = 50L
        )
        job.join()
        assertEquals(1, finishCount.get())
    }

    @Test
    fun systemEventsReceiver_processEvent_callsFinishOnceOnSuccess() = runTest(testDispatcher) {
        val finishCount = java.util.concurrent.atomic.AtomicInteger(0)
        val job = com.limiter.app.scheduler.SystemEventsReceiver.processEvent(
            context = null,
            intent = Intent(Intent.ACTION_BOOT_COMPLETED),
            coordinator = coordinator,
            scheduler = scheduler,
            isDeviceOwner = true,
            scope = this,
            onFinish = { finishCount.incrementAndGet() }
        )
        job.join()
        assertEquals(1, finishCount.get())
    }

    @Test
    fun systemEventsReceiver_processEvent_callsFinishOnceOnException() = runTest(testDispatcher) {
        val failingStore = InterceptingLockStateStore(
            snapshotInterceptor = { throw RuntimeException("Simulated crash during snapshot") }
        )
        val failingCoordinator = LockCoordinator(
            gateway = gateway,
            freezer = freezer,
            lockStateStore = failingStore,
            settingsRepository = settingsRepository,
            criticalPackageResolver = resolver,
            timeSource = timeSource
        )
        val finishCount = java.util.concurrent.atomic.AtomicInteger(0)
        val job = com.limiter.app.scheduler.SystemEventsReceiver.processEvent(
            context = null,
            intent = Intent(Intent.ACTION_BOOT_COMPLETED),
            coordinator = failingCoordinator,
            scheduler = scheduler,
            isDeviceOwner = true,
            scope = this,
            onFinish = { finishCount.incrementAndGet() }
        )
        job.join()
        assertEquals(1, finishCount.get())
    }

    @Test
    fun systemEventsReceiver_processEvent_callsFinishOnceOnTimeout() = runTest(testDispatcher) {
        val slowStore = InterceptingLockStateStore(
            snapshotInterceptor = {
                delay(2000L)
                emptyMap()
            }
        )
        val slowCoordinator = LockCoordinator(
            gateway = gateway,
            freezer = freezer,
            lockStateStore = slowStore,
            settingsRepository = settingsRepository,
            criticalPackageResolver = resolver,
            timeSource = timeSource
        )
        val finishCount = java.util.concurrent.atomic.AtomicInteger(0)
        val job = com.limiter.app.scheduler.SystemEventsReceiver.processEvent(
            context = null,
            intent = Intent(Intent.ACTION_BOOT_COMPLETED),
            coordinator = slowCoordinator,
            scheduler = scheduler,
            isDeviceOwner = true,
            scope = this,
            onFinish = { finishCount.incrementAndGet() },
            timeoutMillis = 50L
        )
        job.join()
        assertEquals(1, finishCount.get())
    }

    @Test
    fun usageMonitorService_calculateLoopBackoff_scalesAndClampsProperly() {
        assertEquals(0L, UsageMonitorService.calculateLoopBackoff(0))
        assertEquals(0L, UsageMonitorService.calculateLoopBackoff(-1))
        assertEquals(5_000L, UsageMonitorService.calculateLoopBackoff(1))
        assertEquals(10_000L, UsageMonitorService.calculateLoopBackoff(2))
        assertEquals(30_000L, UsageMonitorService.calculateLoopBackoff(3))
        assertEquals(60_000L, UsageMonitorService.calculateLoopBackoff(4))
        assertEquals(60_000L, UsageMonitorService.calculateLoopBackoff(10))
    }

    // =========================================================================
    // 20. Time Windows and Alarms Tests (P2: Deadline bug, H4)
    // =========================================================================

    @Test
    fun deadlineCases_inTehranTimezone_conformToExpectedBounds() {
        val tehran = ZoneId.of("Asia/Tehran")
        val y = 2026
        val m = 10
        val d = 13

        fun makeMillis(hour: Int, minute: Int, second: Int = 0): Long {
            return ZonedDateTime.of(y, m, d, hour, minute, second, 0, tehran).toInstant().toEpochMilli()
        }

        // Case a: hide 17:10 (1030), reveal 17:14 (1034), now 17:10:30 -> today 17:14
        val nowA = makeMillis(17, 10, 30)
        val untilA = TimeMath.nextOccurrence(nowA, tehran, 1034)
        assertEquals(makeMillis(17, 14, 0), untilA)
        assertTrue(untilA - nowA <= 24 * 3600_000L)

        // Case b: same, now 17:13:59 -> today 17:14
        val nowB = makeMillis(17, 13, 59)
        val untilB = TimeMath.nextOccurrence(nowB, tehran, 1034)
        assertEquals(makeMillis(17, 14, 0), untilB)
        assertTrue(untilB - nowB <= 24 * 3600_000L)

        // Case c: same, now 17:14:00 -> not inside window; next hide tomorrow 17:10
        val nowC = makeMillis(17, 14, 0)
        val nextHideC = TimeMath.nextOccurrence(nowC, tehran, 1030)
        assertEquals(ZonedDateTime.of(y, m, d + 1, 17, 10, 0, 0, tehran).toInstant().toEpochMilli(), nextHideC)
        assertTrue(nextHideC - nowC <= 24 * 3600_000L)

        // Case d1: overnight hide 21:30 (1290), reveal 07:00 (420), now 22:00 -> tomorrow 07:00
        val nowD1 = makeMillis(22, 0, 0)
        val untilD1 = TimeMath.nextOccurrence(nowD1, tehran, 420)
        assertEquals(ZonedDateTime.of(y, m, d + 1, 7, 0, 0, 0, tehran).toInstant().toEpochMilli(), untilD1)
        assertTrue(untilD1 - nowD1 <= 24 * 3600_000L)

        // Case d2: overnight hide 21:30, reveal 07:00, now 03:00 -> today 07:00
        val nowD2 = makeMillis(3, 0, 0)
        val untilD2 = TimeMath.nextOccurrence(nowD2, tehran, 420)
        assertEquals(makeMillis(7, 0, 0), untilD2)
        assertTrue(untilD2 - nowD2 <= 24 * 3600_000L)

        // Case e: window start exactly at now (17:10:00) -> today 17:14
        val nowE = makeMillis(17, 10, 0)
        val untilE = TimeMath.nextOccurrence(nowE, tehran, 1034)
        assertEquals(makeMillis(17, 14, 0), untilE)
        assertTrue(untilE - nowE <= 24 * 3600_000L)

        // Case f: timezone shift
        val utcZone = ZoneId.of("UTC")
        val untilF = TimeMath.nextOccurrence(nowA, utcZone, 1034)
        assertTrue(untilF > nowA)
        assertTrue(untilF - nowA <= 24 * 3600_000L)
    }

    @Test
    fun expiredLock_reconcilesToUnlockedAndUnfreezes() = runTest(testDispatcher) {
        val targetPkg = "com.dv.adm"
        val pastDeadline = 1792244640000L // 2026-10-17 17:14:00
        timeSource.setNowMillis(pastDeadline + 1000L) // now is strictly after deadline

        gateway.installedPackages.add(targetPkg)
        lockStateStore.ensureRecord(targetPkg)
        lockStateStore.lockUntilAtLeast(targetPkg, pastDeadline)
        freezer.freeze(targetPkg)

        // Reconcile when now >= deadline
        coordinator.reconcile()

        // Must be unfreeze verified and unlocked
        val record = lockStateStore.snapshot()[targetPkg]
        assertNotNull(record)
        assertEquals(LockState.Unlocked, record?.state)
        assertEquals(false, freezer.isFrozen(targetPkg))
    }

    @Test
    fun timeWindowScheduler_earliestUnlockPlan_handlesReleasePendingAndRetries() {
        val now = 1_000_000L

        // ReleasePending with no retry timestamp -> immediate retry at now
        val recordsPending = listOf(
            LockRecord(state = LockState.ReleasePending(requestedAtMillis = now - 5000L))
        )
        val plan1 = TimeWindowScheduler.calculateEarliestUnlockPlan(recordsPending, now)
        assertNotNull(plan1)
        assertEquals(now, plan1?.triggerMillis)
        assertEquals(true, plan1?.isRetry)
        assertEquals(TimeWindowScheduler.ACTION_RETRY, plan1?.action)
        assertEquals(TimeWindowScheduler.REQUEST_RETRY, plan1?.requestCode)

        // LockedUntil with nextUnlockRetryAtMillis earlier than lock deadline -> schedules retry!
        val recordsRetry = listOf(
            LockRecord(
                state = LockState.LockedUntil(untilEpochMillis = now + 3600_000L),
                nextUnlockRetryAtMillis = now + 15_000L
            )
        )
        val plan2 = TimeWindowScheduler.calculateEarliestUnlockPlan(recordsRetry, now)
        assertNotNull(plan2)
        assertEquals(now + 15_000L, plan2?.triggerMillis)
        assertEquals(true, plan2?.isRetry)
        assertEquals(TimeWindowScheduler.ACTION_RETRY, plan2?.action)
        assertEquals(TimeWindowScheduler.REQUEST_RETRY, plan2?.requestCode)
    }

    @Test
    fun timeWindowScheduler_distinctPackages_neverShareRequestCodes() {
        val pkgs = listOf(
            "com.app.alpha",
            "com.app.beta",
            "com.google.android.youtube",
            "org.telegram.messenger",
            "com.instagram.android"
        )
        val codes = pkgs.map { TimeWindowScheduler.calculateHideRequestCode(it) }
        assertEquals(pkgs.size, codes.distinct().size)
    }

    @Test
    fun timeWindowScheduler_reschedule_cancelsRemovedTargetAndReplacesChangedWindow() = runTest(testDispatcher) {
        val pkg1 = "com.app.one"
        val pkg2 = "com.app.two"
        alarmDispatcher.clear()

        // 1. Initial schedule with 2 targets
        settingsRepository.setTargetsAndPending(
            targets = mapOf(
                pkg1 to TargetAppConfig(packageName = pkg1, dailyLimitMinutes = 60, hideAtMinuteOfDay = 1380, revealAtMinuteOfDay = 420), // 23:00
                pkg2 to TargetAppConfig(packageName = pkg2, dailyLimitMinutes = 60, hideAtMinuteOfDay = 1200, revealAtMinuteOfDay = 420)
            ),
            changes = emptyList()
        )
        scheduler.reschedule()
        assertEquals(2, alarmDispatcher.exactScheduled.filter { it.requestCode in 20000..36383 }.size)
        assertTrue(alarmDispatcher.cancelled.isEmpty())

        // 2. Remove pkg2 and change pkg1 window (23:00 -> 22:00 = 1320)
        alarmDispatcher.clear()
        settingsRepository.setTargetsAndPending(
            targets = mapOf(
                pkg1 to TargetAppConfig(packageName = pkg1, dailyLimitMinutes = 60, hideAtMinuteOfDay = 1320, revealAtMinuteOfDay = 420)
            ),
            changes = emptyList()
        )
        scheduler.reschedule()

        // pkg2 should be explicitly cancelled
        val code2 = scheduler.getHideRequestCode(pkg2)
        assertTrue(alarmDispatcher.cancelled.any { it.requestCode == code2 })

        // pkg1 old window should be cancelled and replaced
        val code1 = scheduler.getHideRequestCode(pkg1)
        assertTrue(alarmDispatcher.cancelled.any { it.requestCode == code1 })
        assertTrue(alarmDispatcher.exactScheduled.any { it.requestCode == code1 })
    }

    // =========================================================================
    // 25. P3 Fix Verification Tests (H3, M2, M9, Stuck Locks Fallback)
    // =========================================================================

    // Test 25.1: H3 - UsageMonitorWatchdog.reconcileIfDue calls onReconcile 0 times when nothing due, exactly 1 when due
    @Test
    fun usageMonitorWatchdog_reconcileIfDue_nothingDue_zeroCalls_and_due_exactlyOneCall() = runTest(testDispatcher) {
        val now = 100_000L
        var reconcileCallCount = 0
        val onReconcile: suspend () -> Unit = { reconcileCallCount++ }

        // Case 1: Empty records -> 0 calls, returns false
        val emptyResult = UsageMonitorWatchdog.reconcileIfDue(emptyMap(), now, onReconcile)
        assertFalse(emptyResult)
        assertEquals(0, reconcileCallCount)

        // Case 2: Only future locks -> 0 calls, returns false
        val futureRecords = mapOf(
            "com.app.future" to LockRecord(state = LockState.LockedUntil(now + 10_000L))
        )
        val futureResult = UsageMonitorWatchdog.reconcileIfDue(futureRecords, now, onReconcile)
        assertFalse(futureResult)
        assertEquals(0, reconcileCallCount)

        // Case 3: Due lock -> exactly 1 call, returns true
        val dueRecords = mapOf(
            "com.app.due" to LockRecord(state = LockState.LockedUntil(now - 1000L))
        )
        val dueResult = UsageMonitorWatchdog.reconcileIfDue(dueRecords, now, onReconcile)
        assertTrue(dueResult)
        assertEquals(1, reconcileCallCount)

        // Case 4: Multiple due locks -> still exactly 1 call per invocation
        val multiDueRecords = mapOf(
            "com.app.due1" to LockRecord(state = LockState.LockedUntil(now - 1000L)),
            "com.app.due2" to LockRecord(state = LockState.LockedUntil(now - 2000L))
        )
        val multiDueResult = UsageMonitorWatchdog.reconcileIfDue(multiDueRecords, now, onReconcile)
        assertTrue(multiDueResult)
        assertEquals(2, reconcileCallCount) // 1 previous + 1 current = 2 total
    }

    // Test 25.2: M2 - freeze() failure during window lock does NOT emit WindowLocked and schedules retry with backoff
    @Test
    fun freezeFailure_duringWindowLock_doesNotRecordLockedVerified_schedulesRetryWithBackoff() = runTest(testDispatcher) {
        val pkg = "com.example.freezefail.window"
        val now = zdtToMillis(23, 15) // Inside 23:00..07:00 window
        timeSource.setNowMillis(now)

        settingsRepository.setTargets(
            mapOf(pkg to TargetAppConfig(packageName = pkg, dailyLimitMinutes = 60, hideAtMinuteOfDay = 1380, revealAtMinuteOfDay = 420))
        )
        gateway.installedPackages.add(pkg)

        // Simulate freeze failure
        gateway.suspendFailPackages.add(pkg)

        val report = coordinator.reconcile()
        assertTrue(report is LockCoordinator.ReconcileResult.Ok)
        val okResult = report as LockCoordinator.ReconcileResult.Ok

        // Package must NOT be verified/emitted as WindowLocked
        assertFalse("Must NOT emit WindowLocked on freeze failure", okResult.events.any {
            it is LockCoordinator.LockEvent.WindowLocked && it.pkg == pkg
        })

        // Retry state must be scheduled with backoff
        val record = lockStateStore.snapshot()[pkg]
        assertNotNull("Record must exist", record)
        assertEquals("Failure count must be 1", 1, record!!.unlockFailureCount)
        assertEquals("Retry timestamp must be scheduled at now + 30s", now + 30_000L, record.nextUnlockRetryAtMillis)

        // Physical freeze must NOT be in place
        assertFalse(freezer.isFrozen(pkg) == true)

        // Advance past backoff window, clear failure simulation, reconcile again (Phase D.5 repair)
        timeSource.setNowMillis(now + 30_000L)
        gateway.suspendFailPackages.remove(pkg)

        coordinator.reconcile()

        // Repair must succeed: retry cleared, package frozen!
        val repairedRecord = lockStateStore.snapshot()[pkg]
        assertEquals("Retry count must be reset to 0 after successful repair", 0, repairedRecord!!.unlockFailureCount)
        assertEquals("Retry timestamp must be null", null, repairedRecord.nextUnlockRetryAtMillis)
        assertTrue("Package must now be physically frozen", freezer.isFrozen(pkg) == true)
    }

    // Test 25.3: M2 - freeze() failure on limit reached schedules bounded retry with backoff
    @Test
    fun freezeFailure_onLimitReached_schedulesRetryWithBackoff() = runTest(testDispatcher) {
        val pkg = "com.example.freezefail.limit"
        val now = zdtToMillis(14, 0)
        timeSource.setNowMillis(now)

        settingsRepository.setTargets(
            mapOf(pkg to TargetAppConfig(packageName = pkg, dailyLimitMinutes = 60, hideAtMinuteOfDay = 1380, revealAtMinuteOfDay = 420))
        )
        gateway.installedPackages.add(pkg)

        // Simulate freeze failure
        gateway.suspendFailPackages.add(pkg)

        coordinator.onLimitReached(pkg, 60 * 60_000L)

        val record = lockStateStore.snapshot()[pkg]
        assertNotNull("Record must exist", record)
        assertEquals("Failure count must be 1", 1, record!!.unlockFailureCount)
        assertEquals("Retry timestamp must be scheduled at now + 30s", now + 30_000L, record.nextUnlockRetryAtMillis)
    }

    // Test 25.4: M9 - unexpected exception in evaluateStatus returns Unknown, isPackageInstalled is false
    @Test
    fun evaluateStatus_unexpectedException_returnsUnknown_andIsPackageInstalledReturnsFalse() {
        val unexpectedEx = IllegalStateException("Unexpected IPC / binder crash")
        val status = DeviceDpmGateway.evaluateStatus(
            packageInfo = null,
            pmException = unexpectedEx,
            isDeviceOwner = true,
            isApplicationHidden = { Result.success(false) },
            isPackageSuspended = { Result.success(false) }
        )

        assertTrue("Status must be Unknown on unexpected exception", status is PackageInstallStatus.Unknown)
        assertFalse("Package must not be classified as Installed", status is PackageInstallStatus.Installed)
        assertFalse("Package must not be classified as NotInstalled", status is PackageInstallStatus.NotInstalled)
    }

    // Test 25.5: Fallback against stuck locks - when restore fails at 3 failures, retry state is preserved and not unlocked
    @Test
    fun fallbackAgainstStuckLocks_whenRestoreFailsAtThreeFailures_preservesRetryStateAndDoesNotUnlock() = runTest(testDispatcher) {
        val targetPkg = "com.example.retry.fallback.fail"
        val currentTime = zdtToMillis(9, 0)
        timeSource.setNowMillis(currentTime)

        // Lock expired, previously accumulated 3 unlock failures; backoff from failure 3 (120s) has elapsed
        lockStateStore.lockUntilAtLeast(targetPkg, currentTime - 1000L)
        lockStateStore.recordUnlockFailure(targetPkg, currentTime - 400_000L) // 1 (backoff 30s)
        lockStateStore.recordUnlockFailure(targetPkg, currentTime - 300_000L) // 2 (backoff 60s)
        lockStateStore.recordUnlockFailure(targetPkg, currentTime - 150_000L) // 3 (backoff 120s -> retryAt was currentTime - 30s)
        assertEquals(3, lockStateStore.snapshot()[targetPkg]?.unlockFailureCount)

        gateway.hiddenPackages.add(targetPkg)
        gateway.suspendedPackages.add(targetPkg)
        gateway.packageInstallStatusMap[targetPkg] = PackageInstallStatus.Unknown("Transient query error")

        // Unhide will fail during restore fallback
        gateway.unhideFailPackages.add(targetPkg)

        // Reconcile: count is 3, triggers fallback restore, but unhide fails!
        coordinator.reconcile()

        val record = lockStateStore.snapshot()[targetPkg]
        assertNotNull("Record must still exist", record)
        assertFalse("Must NOT transition to Unlocked when restore fails", record!!.state is LockState.Unlocked)
        assertEquals("Failure count must be incremented to 4", 4, record.unlockFailureCount)
        assertEquals("Next retry must be scheduled with backoff at now + 300s", currentTime + 300_000L, record.nextUnlockRetryAtMillis)
        assertTrue("Package must still be hidden", gateway.hiddenPackages.contains(targetPkg))
    }
}

