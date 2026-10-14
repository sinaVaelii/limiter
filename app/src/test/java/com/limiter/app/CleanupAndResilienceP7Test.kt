package com.limiter.app

import android.content.Intent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.limiter.app.data.CriticalPackageResolver
import com.limiter.app.data.InMemoryLockStateStore
import com.limiter.app.data.SettingsRepository
import com.limiter.app.dpm.AppFreezer
import com.limiter.app.dpm.FakeDpmGateway
import com.limiter.app.model.LockRecord
import com.limiter.app.model.LockState
import com.limiter.app.model.TargetAppConfig
import com.limiter.app.policy.LockCoordinator
import com.limiter.app.scheduler.AlarmDispatcher
import com.limiter.app.scheduler.AlarmReceiver
import com.limiter.app.scheduler.IntentFactory
import com.limiter.app.scheduler.SystemEventsReceiver
import com.limiter.app.scheduler.TimeWindowScheduler
import com.limiter.app.service.UsageMonitorWatchdog
import com.limiter.app.time.FakeTimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * P7: JVM Unit Tests for Scheduler Cancellation, Watchdog Decision Logic,
 * and Broadcast Receiver Completion Resilience.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CleanupAndResilienceP7Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val utc = ZoneId.of("UTC")

    private lateinit var lockStateStore: InMemoryLockStateStore
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var timeSource: FakeTimeSource
    private lateinit var alarmDispatcher: TestAlarmDispatcher
    private lateinit var intentFactory: TestIntentFactory
    private lateinit var scheduler: TimeWindowScheduler
    private lateinit var coordinator: LockCoordinator

    class TestAlarmDispatcher : AlarmDispatcher {
        var canScheduleExact = true
        val exactScheduled = mutableListOf<ScheduledAlarm>()
        val inexactScheduled = mutableListOf<ScheduledAlarm>()
        val cancelled = mutableListOf<CancelledAlarm>()

        data class ScheduledAlarm(val triggerMillis: Long, val intent: Intent, val requestCode: Int)
        data class CancelledAlarm(val intent: Intent, val requestCode: Int)

        override fun canScheduleExactAlarms(): Boolean = canScheduleExact

        override fun scheduleExact(triggerMillis: Long, intent: Intent, requestCode: Int) {
            exactScheduled.add(ScheduledAlarm(triggerMillis, intent, requestCode))
        }

        override fun scheduleInexact(triggerMillis: Long, intent: Intent, requestCode: Int) {
            inexactScheduled.add(ScheduledAlarm(triggerMillis, intent, requestCode))
        }

        override fun cancel(intent: Intent, requestCode: Int) {
            cancelled.add(CancelledAlarm(intent, requestCode))
        }

        fun clear() {
            exactScheduled.clear()
            inexactScheduled.clear()
            cancelled.clear()
        }
    }

    class TestIntentFactory : IntentFactory {
        override fun createHideIntent(pkg: String): Intent = Intent(TimeWindowScheduler.ACTION_HIDE)
        override fun createUnlockIntent(): Intent = Intent(TimeWindowScheduler.ACTION_UNLOCK)
        override fun createMidnightIntent(): Intent = Intent(TimeWindowScheduler.ACTION_MIDNIGHT)
        override fun createRetryIntent(): Intent = Intent(TimeWindowScheduler.ACTION_RETRY)
    }

    class FakeCriticalPackageResolver : CriticalPackageResolver {
        override fun resolveProtected(): Set<String> = emptySet()
        override fun isEligibleTarget(packageName: String): Boolean = true
    }

    private fun zdtToMillis(hour: Int, minute: Int): Long {
        return ZonedDateTime.of(2026, 10, 14, hour, minute, 0, 0, utc)
            .toInstant().toEpochMilli()
    }

    @Before
    fun setup() {
        lockStateStore = InMemoryLockStateStore()
        val settingsFile = tempFolder.newFile("test_settings_p7.preferences_pb")
        val settingsDataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { settingsFile }
        )
        settingsRepository = SettingsRepository(settingsDataStore)

        timeSource = FakeTimeSource(zdtToMillis(12, 0), utc)
        alarmDispatcher = TestAlarmDispatcher()
        intentFactory = TestIntentFactory()

        scheduler = TimeWindowScheduler(
            context = null,
            settingsRepository = settingsRepository,
            lockStateStore = lockStateStore,
            timeSource = timeSource,
            alarmDispatcher = alarmDispatcher,
            intentFactory = intentFactory
        )

        val gateway = FakeDpmGateway().apply { deviceOwner = true }
        val freezer = AppFreezer(gateway)
        val resolver = FakeCriticalPackageResolver()
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
    // 1. Scheduler Cancellation Tests (T1)
    // =========================================================================

    @Test
    fun scheduler_targetRemoval_activelyCancelsHideAlarmOnDispatcher() = runTest(testDispatcher) {
        val pkgA = "com.test.target.a"
        val pkgB = "com.test.target.b"
        val configA = TargetAppConfig(packageName = pkgA, dailyLimitMinutes = 60, hideAtMinuteOfDay = 1380, revealAtMinuteOfDay = 420)
        val configB = TargetAppConfig(packageName = pkgB, dailyLimitMinutes = 60, hideAtMinuteOfDay = 1320, revealAtMinuteOfDay = 480)

        // Add both targets and reschedule
        settingsRepository.putTarget(configA)
        settingsRepository.putTarget(configB)
        scheduler.reschedule()

        val hideCodeA = TimeWindowScheduler.calculateHideRequestCode(pkgA)
        val hideCodeB = TimeWindowScheduler.calculateHideRequestCode(pkgB)

        // Clear recorded actions
        alarmDispatcher.clear()

        // Remove target A
        settingsRepository.removeTarget(pkgA)
        scheduler.reschedule()

        // Verify alarmDispatcher.cancel was called specifically for pkgA's hide request code
        val cancelledCodeA = alarmDispatcher.cancelled.any { it.requestCode == hideCodeA }
        val cancelledCodeB = alarmDispatcher.cancelled.any { it.requestCode == hideCodeB }

        assertTrue("Hide alarm for removed target A must be cancelled", cancelledCodeA)
        assertFalse("Hide alarm for remaining target B must NOT be cancelled", cancelledCodeB)
    }

    @Test
    fun scheduler_windowChange_cancelsPreviousHideAlarmBeforeRescheduling() = runTest(testDispatcher) {
        val pkg = "com.test.window.change"
        val config1 = TargetAppConfig(packageName = pkg, dailyLimitMinutes = 60, hideAtMinuteOfDay = 1380, revealAtMinuteOfDay = 420)
        settingsRepository.putTarget(config1)
        scheduler.reschedule()

        alarmDispatcher.clear()

        // Change hide window to 22:00 (1320)
        val config2 = TargetAppConfig(packageName = pkg, dailyLimitMinutes = 60, hideAtMinuteOfDay = 1320, revealAtMinuteOfDay = 420)
        settingsRepository.putTarget(config2)
        scheduler.reschedule()

        val hideCode = TimeWindowScheduler.calculateHideRequestCode(pkg)
        val cancelled = alarmDispatcher.cancelled.any { it.requestCode == hideCode }
        assertTrue("Previous hide alarm must be cancelled when window changes", cancelled)
    }

    @Test
    fun scheduler_cancelHideAlarm_directlyCancelsCorrectRequestCode() {
        val pkg = "com.test.direct.cancel"
        val expectedCode = TimeWindowScheduler.calculateHideRequestCode(pkg)

        scheduler.cancelHideAlarm(pkg)

        assertEquals(1, alarmDispatcher.cancelled.size)
        assertEquals(expectedCode, alarmDispatcher.cancelled.first().requestCode)
    }

    @Test
    fun scheduler_cancelAll_cancelsSystemAlarmsAndAllTrackedHideAlarms() = runTest(testDispatcher) {
        val pkg1 = "com.test.all.one"
        val pkg2 = "com.test.all.two"
        settingsRepository.putTarget(TargetAppConfig(pkg1, 60, 1380, 420))
        settingsRepository.putTarget(TargetAppConfig(pkg2, 60, 1320, 480))
        scheduler.reschedule()

        alarmDispatcher.clear()

        scheduler.cancelAll()

        val cancelledCodes = alarmDispatcher.cancelled.map { it.requestCode }.toSet()
        assertTrue("Must cancel midnight alarm", cancelledCodes.contains(TimeWindowScheduler.REQUEST_MIDNIGHT))
        assertTrue("Must cancel unlock alarm", cancelledCodes.contains(TimeWindowScheduler.REQUEST_UNLOCK))
        assertTrue("Must cancel retry alarm", cancelledCodes.contains(TimeWindowScheduler.REQUEST_RETRY))
        assertTrue("Must cancel pkg1 hide alarm", cancelledCodes.contains(TimeWindowScheduler.calculateHideRequestCode(pkg1)))
        assertTrue("Must cancel pkg2 hide alarm", cancelledCodes.contains(TimeWindowScheduler.calculateHideRequestCode(pkg2)))
    }

    // =========================================================================
    // 2. Service Watchdog Decision Logic Tests (T1)
    // =========================================================================

    @Test
    fun watchdog_reconcileIfDue_executesCallbackOnlyWhenDue() = runTest(testDispatcher) {
        val now = 100_000L
        val callCount = AtomicInteger(0)
        val onReconcile: suspend () -> Unit = { callCount.incrementAndGet() }

        // 1. Empty records -> false, 0 calls
        val res1 = UsageMonitorWatchdog.reconcileIfDue(emptyMap(), now, onReconcile)
        assertFalse(res1)
        assertEquals(0, callCount.get())

        // 2. Future lock -> false, 0 calls
        val futureRecords = mapOf("pkg" to LockRecord(LockState.LockedUntil(now + 10_000L)))
        val res2 = UsageMonitorWatchdog.reconcileIfDue(futureRecords, now, onReconcile)
        assertFalse(res2)
        assertEquals(0, callCount.get())

        // 3. Due lock -> true, 1 call
        val dueRecords = mapOf("pkg" to LockRecord(LockState.LockedUntil(now - 1L)))
        val res3 = UsageMonitorWatchdog.reconcileIfDue(dueRecords, now, onReconcile)
        assertTrue(res3)
        assertEquals(1, callCount.get())

        // 4. ReleasePending with null retry -> immediately due
        val pendingNullRetry = mapOf("pkg" to LockRecord(LockState.ReleasePending(1000L)))
        val res4 = UsageMonitorWatchdog.reconcileIfDue(pendingNullRetry, now, onReconcile)
        assertTrue(res4)
        assertEquals(2, callCount.get())

        // 5. ReleasePending with future retry -> not due
        val pendingFutureRetry = mapOf(
            "pkg" to LockRecord(
                state = LockState.ReleasePending(1000L),
                nextUnlockRetryAtMillis = now + 5000L
            )
        )
        val res5 = UsageMonitorWatchdog.reconcileIfDue(pendingFutureRetry, now, onReconcile)
        assertFalse(res5)
        assertEquals(2, callCount.get())

        // 6. ReleasePending with elapsed retry -> due
        val pendingElapsedRetry = mapOf(
            "pkg" to LockRecord(
                state = LockState.ReleasePending(1000L),
                nextUnlockRetryAtMillis = now
            )
        )
        val res6 = UsageMonitorWatchdog.reconcileIfDue(pendingElapsedRetry, now, onReconcile)
        assertTrue(res6)
        assertEquals(3, callCount.get())
    }

    // =========================================================================
    // 3. Receiver Completion & Resilience Tests (T1)
    // =========================================================================

    @Test
    fun alarmReceiver_unhandledAction_invokesFinishExactlyOnce() = runTest(testDispatcher) {
        val finishInvoked = AtomicBoolean(false)
        val finishCount = AtomicInteger(0)

        val job = AlarmReceiver.processAlarm(
            context = null,
            intent = Intent("com.unknown.action"),
            coordinator = coordinator,
            scheduler = scheduler,
            isDeviceOwner = true,
            scope = this,
            onFinish = {
                finishInvoked.set(true)
                finishCount.incrementAndGet()
            }
        )

        job.join()
        assertTrue("onFinish must be invoked even for unhandled action", finishInvoked.get())
        assertEquals(1, finishCount.get())
    }

    @Test
    fun systemEventsReceiver_unhandledAction_invokesFinishExactlyOnce() = runTest(testDispatcher) {
        val finishInvoked = AtomicBoolean(false)
        val finishCount = AtomicInteger(0)

        val job = SystemEventsReceiver.processEvent(
            context = null,
            intent = Intent("android.intent.action.BATTERY_LOW"),
            coordinator = coordinator,
            scheduler = scheduler,
            isDeviceOwner = true,
            scope = this,
            onFinish = {
                finishInvoked.set(true)
                finishCount.incrementAndGet()
            }
        )

        job.join()
        assertTrue("onFinish must be invoked even for unhandled system action", finishInvoked.get())
        assertEquals(1, finishCount.get())
    }
}
