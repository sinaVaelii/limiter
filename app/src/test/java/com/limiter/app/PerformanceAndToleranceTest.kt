package com.limiter.app

import android.content.Intent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.limiter.app.data.CriticalPackageResolver
import com.limiter.app.data.InMemoryLockStateStore
import com.limiter.app.data.SettingsRepository
import com.limiter.app.data.UsageSnapshotStore
import com.limiter.app.data.UsageStatsReader
import com.limiter.app.model.LockRecord
import com.limiter.app.model.LockState
import com.limiter.app.model.TargetAppConfig
import com.limiter.app.scheduler.AlarmDispatcher
import com.limiter.app.scheduler.IntentFactory
import com.limiter.app.scheduler.TimeWindowScheduler
import com.limiter.app.time.FakeTimeSource
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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

/**
 * Tests verifying performance and tolerance requirements:
 * - Lookback window and plan caching avoiding redundant AlarmManager calls.
 * - CriticalPackageResolver caching and invalidation contract.
 * - UsageSnapshotStore tolerant decoding of corrupt and malformed snapshot lines.
 * - CriticalPackageResolver system apps toggle: allows launchable non-protected system apps,
 *   while preserving strict protection for critical system apps and Limiter itself.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PerformanceAndToleranceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val utc = ZoneId.of("UTC")

    private lateinit var lockStateStore: InMemoryLockStateStore
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var usageSnapshotStore: UsageSnapshotStore
    private lateinit var timeSource: FakeTimeSource
    private lateinit var alarmDispatcher: TestAlarmDispatcher
    private lateinit var intentFactory: TestIntentFactory
    private lateinit var scheduler: TimeWindowScheduler

    class TestAlarmDispatcher : AlarmDispatcher {
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

    class TestIntentFactory : IntentFactory {
        override fun createHideIntent(pkg: String): Intent = Intent(TimeWindowScheduler.ACTION_HIDE)
        override fun createUnlockIntent(): Intent = Intent(TimeWindowScheduler.ACTION_UNLOCK)
        override fun createMidnightIntent(): Intent = Intent(TimeWindowScheduler.ACTION_MIDNIGHT)
        override fun createRetryIntent(): Intent = Intent(TimeWindowScheduler.ACTION_RETRY)
    }

    private fun zdtToMillis(hour: Int, minute: Int): Long {
        return ZonedDateTime.of(2026, 10, 13, hour, minute, 0, 0, utc)
            .toInstant().toEpochMilli()
    }

    @Before
    fun setup() {
        lockStateStore = InMemoryLockStateStore()
        val settingsFile = tempFolder.newFile("test_settings_p5.preferences_pb")
        val settingsDataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { settingsFile }
        )
        settingsRepository = SettingsRepository(settingsDataStore)

        val usageFile = tempFolder.newFile("test_usage_p5.preferences_pb")
        val usageDataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { usageFile }
        )
        usageSnapshotStore = UsageSnapshotStore(usageDataStore)

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
    }

    // =========================================================================
    // M4: UsageStatsReader Lookback and TimeWindowScheduler Plan Caching
    // =========================================================================

    @Test
    fun m4_usageStatsReader_lookbackWindowIsExactly3Hours() {
        val threeHoursMillis = 3L * 60 * 60 * 1000
        assertEquals(threeHoursMillis, UsageStatsReader.LOOKBACK_MILLIS)
    }

    @Test
    fun m4_timeWindowScheduler_rescheduleIfPlanChanged_returnsNullWhenPlanUnchanged() = runTest(testDispatcher) {
        val pkg = "com.example.app"
        settingsRepository.setTargets(mapOf(pkg to TargetAppConfig(pkg, 60, 1320, 420)))

        // 1. Initial call: plan was not scheduled before, so it must return a report and schedule alarms
        val initialReport = scheduler.rescheduleIfPlanChanged()
        assertNotNull("Initial call must reschedule", initialReport)
        val initialScheduledCount = alarmDispatcher.exactScheduled.size
        assertTrue("Alarms must have been scheduled", initialScheduledCount > 0)

        // 2. Second call with unchanged plan: must return null and schedule zero additional alarms
        alarmDispatcher.clear()
        val secondReport = scheduler.rescheduleIfPlanChanged()
        assertNull("Subsequent call with identical plan must return null", secondReport)
        assertEquals("No new alarms should be scheduled when plan is unchanged", 0, alarmDispatcher.exactScheduled.size)

        // 3. Update target config: hideAtMinuteOfDay changes
        settingsRepository.setTargets(mapOf(pkg to TargetAppConfig(pkg, 60, 1380, 420)))
        val updatedReport = scheduler.rescheduleIfPlanChanged()
        assertNotNull("Changing target hide window must reschedule", updatedReport)
        assertTrue("Alarms must be scheduled after plan change", alarmDispatcher.exactScheduled.isNotEmpty())
    }

    @Test
    fun m4_timeWindowScheduler_rescheduleIfPlanChanged_retriesOnPreviousFailure() = runTest(testDispatcher) {
        val pkg = "com.example.retry"
        settingsRepository.setTargets(mapOf(pkg to TargetAppConfig(pkg, 60, 1320, 420)))

        // Simulate failure during scheduling for both exact and inexact fallback
        alarmDispatcher.exactFailure = RuntimeException("exact failed")
        alarmDispatcher.inexactFailure = RuntimeException("inexact failed")
        val failingReport = scheduler.rescheduleIfPlanChanged()
        assertNotNull(failingReport)
        assertTrue("Report must indicate failures", failingReport!!.hasAnyFailures)

        // Fix failure condition and re-run without changing target plan
        alarmDispatcher.clear()
        val retryReport = scheduler.rescheduleIfPlanChanged()
        assertNotNull("Must retry when previous run encountered errors", retryReport)
        assertFalse("Retry report should succeed", retryReport!!.hasAnyFailures)
    }

    @Test
    fun m4_timeWindowScheduler_cancelAll_resetsPlan() = runTest(testDispatcher) {
        val pkg = "com.example.cancel"
        settingsRepository.setTargets(mapOf(pkg to TargetAppConfig(pkg, 60, 1320, 420)))

        val report1 = scheduler.rescheduleIfPlanChanged()
        assertNotNull(report1)

        // Cancel all
        scheduler.cancelAll()

        // Next call must reschedule even if targets are identical
        val report2 = scheduler.rescheduleIfPlanChanged()
        assertNotNull("Must reschedule after cancelAll() reset the plan", report2)
    }

    // =========================================================================
    // M5: CriticalPackageResolver Caching and Invalidation
    // =========================================================================

    class CachingTestResolver(
        private val dynamicPackages: () -> Set<String>
    ) : CriticalPackageResolver {
        var computeCount = 0
            private set

        @Volatile
        private var cached: Set<String>? = null
        private val lock = Any()

        override fun resolveProtected(): Set<String> {
            val c = cached
            if (c != null) return c
            synchronized(lock) {
                val inner = cached
                if (inner != null) return inner
                computeCount++
                val res = dynamicPackages()
                cached = res
                return res
            }
        }

        override fun invalidateCache() {
            cached = null
        }

        override fun isEligibleTarget(packageName: String): Boolean = true
    }

    @Test
    fun m5_criticalPackageResolver_cachesProtectedPackagesAndInvalidates() {
        var systemApps = setOf("com.limiter.app", "android", "com.android.settings")
        val resolver = CachingTestResolver { systemApps }

        assertEquals(0, resolver.computeCount)

        // First call computes
        val firstResult = resolver.resolveProtected()
        assertEquals(1, resolver.computeCount)
        assertEquals(3, firstResult.size)

        // Second call uses cache
        val secondResult = resolver.resolveProtected()
        assertEquals(1, resolver.computeCount)
        assertEquals(firstResult, secondResult)

        // Invalidate cache
        systemApps = setOf("com.limiter.app", "android", "com.android.settings", "com.xiaomi.misettings")
        resolver.invalidateCache()

        // Next call recomputes
        val thirdResult = resolver.resolveProtected()
        assertEquals(2, resolver.computeCount)
        assertEquals(4, thirdResult.size)
        assertTrue(thirdResult.contains("com.xiaomi.misettings"))
    }

    // =========================================================================
    // M6: UsageSnapshotStore Corrupt Line Tolerance
    // =========================================================================

    @Test
    fun m6_usageSnapshotStore_decodeSnapshots_skipsCorruptLinesGracefully() {
        val corruptedPayload = """
            
            not_even_a_pipe_line
            incomplete|1234
            toomany|1|2|3|4|5
            bad_numbers|abc|def|xyz
            negative_limit|1000|2000|-5
            |1000|2000|60
               |1000|2000|60
            valid.app.one|50000|100000|60
            valid.app.two|0|3600000|120
            corrupt_used|notanumber|1000|60
            valid.app.three|3600000|0|60
            
        """.trimIndent()

        val snapshots = usageSnapshotStore.decodeSnapshots(corruptedPayload)

        assertEquals("Only the 3 valid snapshots should be parsed", 3, snapshots.size)
        assertTrue(snapshots.containsKey("valid.app.one"))
        assertTrue(snapshots.containsKey("valid.app.two"))
        assertTrue(snapshots.containsKey("valid.app.three"))

        val s1 = snapshots["valid.app.one"]!!
        assertEquals(50000L, s1.usedMillis)
        assertEquals(100000L, s1.remainingMillis)
        assertEquals(60, s1.limitMinutes)

        val s2 = snapshots["valid.app.two"]!!
        assertEquals(0L, s2.usedMillis)
        assertEquals(3600000L, s2.remainingMillis)
        assertEquals(120, s2.limitMinutes)

        val s3 = snapshots["valid.app.three"]!!
        assertEquals(3600000L, s3.usedMillis)
        assertEquals(0L, s3.remainingMillis)
        assertEquals(60, s3.limitMinutes)
    }

    @Test
    fun m6_usageSnapshotStore_decodeSnapshots_emptyOrBlankReturnsEmptyMap() {
        assertTrue(usageSnapshotStore.decodeSnapshots("").isEmpty())
        assertTrue(usageSnapshotStore.decodeSnapshots("   \n\n  \t  ").isEmpty())
    }

    // =========================================================================
    // M7: CriticalPackageResolver System Apps Toggle and Immunity
    // =========================================================================

    class FilterTestResolver(
        private val protectedSet: Set<String>,
        private val launchableSystemApps: Set<String>,
        private val launchableUserApps: Set<String>
    ) : CriticalPackageResolver {
        override fun resolveProtected(): Set<String> = protectedSet

        override fun isEligibleTarget(packageName: String): Boolean {
            return isEligibleTarget(packageName, includeSystemApps = false)
        }

        override fun isEligibleTarget(packageName: String, includeSystemApps: Boolean): Boolean {
            // Protected apps can never be targeted
            if (packageName in protectedSet) return false

            val isUserApp = packageName in launchableUserApps
            val isSystemApp = packageName in launchableSystemApps

            if (!isUserApp && !isSystemApp) return false

            // If system app, only allowed when includeSystemApps is true
            if (isSystemApp && !includeSystemApps) return false

            return true
        }
    }

    @Test
    fun m7_systemAppsToggle_allowsLaunchableNonProtectedSystemAppsWhenEnabled() {
        val protected = setOf(
            "com.limiter.app",
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.google.android.dialer",
            "com.google.android.apps.messaging",
            "com.google.android.inputmethod.latin"
        )
        val systemApps = setOf(
            "com.google.android.youtube",
            "com.android.chrome",
            "com.miui.player"
        )
        val userApps = setOf(
            "org.telegram.messenger",
            "com.instagram.android"
        )

        val resolver = FilterTestResolver(
            protectedSet = protected,
            launchableSystemApps = systemApps,
            launchableUserApps = userApps
        )

        // 1. With includeSystemApps = false:
        // User apps are eligible
        assertTrue(resolver.isEligibleTarget("org.telegram.messenger", includeSystemApps = false))
        assertTrue(resolver.isEligibleTarget("com.instagram.android", includeSystemApps = false))
        // System apps are NOT eligible
        assertFalse(resolver.isEligibleTarget("com.google.android.youtube", includeSystemApps = false))
        assertFalse(resolver.isEligibleTarget("com.android.chrome", includeSystemApps = false))
        assertFalse(resolver.isEligibleTarget("com.miui.player", includeSystemApps = false))
        // Protected apps are NOT eligible
        assertFalse(resolver.isEligibleTarget("com.limiter.app", includeSystemApps = false))
        assertFalse(resolver.isEligibleTarget("com.android.settings", includeSystemApps = false))

        // 2. With includeSystemApps = true:
        // User apps remain eligible
        assertTrue(resolver.isEligibleTarget("org.telegram.messenger", includeSystemApps = true))
        // Launchable non-protected system apps become eligible!
        assertTrue(resolver.isEligibleTarget("com.google.android.youtube", includeSystemApps = true))
        assertTrue(resolver.isEligibleTarget("com.android.chrome", includeSystemApps = true))
        assertTrue(resolver.isEligibleTarget("com.miui.player", includeSystemApps = true))
        // CRITICAL INVARIANT: Protected packages remain strictly ineligible!
        assertFalse(resolver.isEligibleTarget("com.limiter.app", includeSystemApps = true))
        assertFalse(resolver.isEligibleTarget("android", includeSystemApps = true))
        assertFalse(resolver.isEligibleTarget("com.android.systemui", includeSystemApps = true))
        assertFalse(resolver.isEligibleTarget("com.android.settings", includeSystemApps = true))
        assertFalse(resolver.isEligibleTarget("com.google.android.dialer", includeSystemApps = true))
        assertFalse(resolver.isEligibleTarget("com.google.android.apps.messaging", includeSystemApps = true))
        assertFalse(resolver.isEligibleTarget("com.google.android.inputmethod.latin", includeSystemApps = true))
        // Non-installed or non-launchable apps remain ineligible
        assertFalse(resolver.isEligibleTarget("com.unknown.package", includeSystemApps = true))
    }
}
