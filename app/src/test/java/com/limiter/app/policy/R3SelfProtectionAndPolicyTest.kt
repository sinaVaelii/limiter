package com.limiter.app.policy

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.limiter.app.data.CriticalPackageResolver
import com.limiter.app.data.InMemoryLockStateStore
import com.limiter.app.data.SettingsRepository
import com.limiter.app.dpm.AppFreezer
import com.limiter.app.dpm.DpmOperationResult
import com.limiter.app.dpm.FakeDpmGateway
import com.limiter.app.model.LockRecord
import com.limiter.app.model.LockState
import com.limiter.app.model.TargetAppConfig
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
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Stage R3 tests for self-protection, safe device policy, and protected package invariants
 * per AGENTS.md §24, §45, §46, and Section C (R3).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class R3SelfProtectionAndPolicyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var gateway: FakeDpmGateway
    private lateinit var freezer: AppFreezer
    private lateinit var lockStateStore: InMemoryLockStateStore
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var timeSource: FakeTimeSource
    private lateinit var resolver: FakeCriticalPackageResolver
    private lateinit var coordinator: LockCoordinator

    private val selfPkg = "com.limiter.app"
    private val utc = ZoneId.of("UTC")
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    class FakeCriticalPackageResolver : CriticalPackageResolver {
        val protectedPackages = mutableSetOf<String>()
        val userInstalledPackages = mutableSetOf<String>()

        override fun resolveProtected(): Set<String> = protectedPackages

        override fun isEligibleTarget(packageName: String): Boolean {
            return packageName in userInstalledPackages && packageName !in protectedPackages
        }
    }

    private fun zdtToMillis(hour: Int, minute: Int): Long {
        return ZonedDateTime.of(2026, 10, 11, hour, minute, 0, 0, utc)
            .toInstant().toEpochMilli()
    }

    @Before
    fun setup() {
        gateway = FakeDpmGateway()
        gateway.deviceOwner = true
        gateway.installedPackages.add(selfPkg)

        freezer = AppFreezer(gateway)
        lockStateStore = InMemoryLockStateStore()

        val dataStoreFile = tempFolder.newFile("test_settings_r3.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { dataStoreFile }
        )
        settingsRepository = SettingsRepository(dataStore)

        timeSource = FakeTimeSource()
        timeSource.setNowMillis(zdtToMillis(12, 0))
        timeSource.setZone(utc)

        resolver = FakeCriticalPackageResolver()
        resolver.protectedPackages.add(selfPkg)
        resolver.protectedPackages.add("android")
        resolver.protectedPackages.add("com.android.systemui")
        resolver.protectedPackages.add("com.android.settings")

        coordinator = LockCoordinator(
            gateway = gateway,
            freezer = freezer,
            lockStateStore = lockStateStore,
            settingsRepository = settingsRepository,
            criticalPackageResolver = resolver,
            timeSource = timeSource,
            selfPackageName = selfPkg
        )
    }

    // =======================================================================
    // 1. Targeted Self-Protection Enforcement (§24, §45, §C R3)
    // =======================================================================

    @Test
    fun `self-protection - applied when Device Owner is active`() = runTest {
        val result = coordinator.enforceSelfProtection()

        assertEquals(LockCoordinator.SelfProtectionResult.Applied, result)
        assertEquals(true, gateway.isUninstallBlocked(selfPkg).getOrNull())
        assertTrue(gateway.getUserControlDisabledPackages().contains(selfPkg))
    }

    @Test
    fun `reconcile - enforces self-protection during normal reconciliation`() = runTest {
        val result = coordinator.reconcile()

        assertTrue(result is LockCoordinator.ReconcileResult.Ok)
        val ok = result as LockCoordinator.ReconcileResult.Ok
        assertEquals(LockCoordinator.SelfProtectionResult.Applied, ok.selfProtection)

        assertEquals(true, gateway.isUninstallBlocked(selfPkg).getOrNull())
        assertTrue(gateway.getUserControlDisabledPackages().contains(selfPkg))
    }

    @Test
    fun `self-protection - Device Owner absent produces explicit NotDeviceOwner without claims`() = runTest {
        gateway.deviceOwner = false

        val selfProtection = coordinator.enforceSelfProtection()
        assertEquals(LockCoordinator.SelfProtectionResult.NotDeviceOwner, selfProtection)

        val reconcileResult = coordinator.reconcile()
        assertEquals(LockCoordinator.ReconcileResult.NotDeviceOwner, reconcileResult)

        // No policies should have been applied
        assertFalse(gateway.uninstallBlockedPackages.contains(selfPkg))
        assertFalse(gateway.disabledUserControlPackages.contains(selfPkg))
    }

    @Test
    fun `self-protection - uninstall blocked failure reports PartialOrFailed`() = runTest {
        gateway.uninstallBlockedFailPackages.add(selfPkg)

        val result = coordinator.enforceSelfProtection()
        assertTrue(result is LockCoordinator.SelfProtectionResult.PartialOrFailed)

        val failed = result as LockCoordinator.SelfProtectionResult.PartialOrFailed
        assertTrue(failed.uninstallResult is DpmOperationResult.Failed)
        assertEquals(DpmOperationResult.Ok, failed.userControlResult)
    }

    @Test
    fun `self-protection - user control failure reports PartialOrFailed`() = runTest {
        gateway.userControlFail = true

        val result = coordinator.enforceSelfProtection()
        assertTrue(result is LockCoordinator.SelfProtectionResult.PartialOrFailed)

        val failed = result as LockCoordinator.SelfProtectionResult.PartialOrFailed
        assertEquals(DpmOperationResult.Ok, failed.uninstallResult)
        assertTrue(failed.userControlResult is DpmOperationResult.Failed)
    }

    // =======================================================================
    // 2. Protected Packages Safety & Transitions (§36, §46, §C R3)
    // =======================================================================

    @Test
    fun `protected package - tryEditConfig rejects adding protected app`() = runTest {
        val config = TargetAppConfig("com.android.settings", 60, 0, 1439)
        val result = coordinator.tryEditConfig(config)

        assertEquals(SettingsPolicy.EditResult.Protected, result)
        assertFalse(settingsRepository.snapshotTargets().containsKey("com.android.settings"))
    }

    @Test
    fun `protected package - tryEditConfig rejects editing target that became protected`() = runTest {
        val pkg = "com.example.target"
        val initialConfig = TargetAppConfig(pkg, 60, 23 * 60, 7 * 60)
        settingsRepository.putTarget(initialConfig)

        // Target becomes protected (e.g. user designated as device admin or IME)
        resolver.protectedPackages.add(pkg)

        val editConfig = TargetAppConfig(pkg, 30, 23 * 60, 7 * 60)
        val result = coordinator.tryEditConfig(editConfig)

        assertEquals(SettingsPolicy.EditResult.Protected, result)
    }

    @Test
    fun `protected package - onLimitReached ignores protected package`() = runTest {
        val pkg = "com.android.settings"
        gateway.installedPackages.add(pkg)
        settingsRepository.putTarget(TargetAppConfig(pkg, 60, 0, 1439))

        coordinator.onLimitReached(pkg, 70 * 60 * 1000L)

        // Must NOT lock or freeze
        assertNull(lockStateStore.snapshot()[pkg])
        assertFalse(gateway.hiddenPackages.contains(pkg))
        assertFalse(gateway.suspendedPackages.contains(pkg))
    }

    @Test
    fun `protected package - scheduled window ignores protected package`() = runTest {
        val pkg = "com.android.settings"
        gateway.installedPackages.add(pkg)
        // All-day lock window
        settingsRepository.putTarget(TargetAppConfig(pkg, 60, 0, 1439))

        coordinator.reconcile()

        assertNull(lockStateStore.snapshot()[pkg])
        assertFalse(gateway.hiddenPackages.contains(pkg))
        assertFalse(gateway.suspendedPackages.contains(pkg))
    }

    @Test
    fun `protected package - already locked package expires normally without early unlock`() = runTest {
        val pkg = "com.example.app"
        gateway.installedPackages.add(pkg)
        gateway.hiddenPackages.add(pkg)
        gateway.suspendedPackages.add(pkg)

        val lockUntil = zdtToMillis(14, 0)
        lockStateStore.ensureRecord(pkg)
        lockStateStore.lockUntilAtLeast(pkg, lockUntil)

        // Target becomes protected during active lock
        resolver.protectedPackages.add(pkg)

        // At 12:00, lock is active
        coordinator.reconcile()

        // MUST NOT unlock early merely because it became protected (§C R3 item 5)
        assertTrue(gateway.hiddenPackages.contains(pkg))
        assertTrue(gateway.suspendedPackages.contains(pkg))
        val record = lockStateStore.snapshot()[pkg]
        assertTrue(record?.state is LockState.LockedUntil)

        // Fast forward to expiry (14:01)
        timeSource.setNowMillis(zdtToMillis(14, 1))
        coordinator.reconcile()

        // On expiry, unfreeze and unlock
        assertFalse(gateway.hiddenPackages.contains(pkg))
        assertFalse(gateway.suspendedPackages.contains(pkg))
        assertEquals(LockState.Unlocked, lockStateStore.snapshot()[pkg]?.state)

        // Verify it remains excluded from future locks
        settingsRepository.putTarget(TargetAppConfig(pkg, 60, 0, 1439))
        coordinator.reconcile()
        assertFalse(gateway.hiddenPackages.contains(pkg))
        assertFalse(gateway.suspendedPackages.contains(pkg))
    }

    @Test
    fun `protected package - orphan restricted package without lock record is recovered`() = runTest {
        val pkg = "com.android.settings"
        gateway.installedPackages.add(pkg)
        gateway.hiddenPackages.add(pkg)
        gateway.suspendedPackages.add(pkg)

        // Record in targets so it's in managed history / recovery set
        settingsRepository.putTarget(TargetAppConfig(pkg, 60, 0, 1439))

        // Phase H recovery
        coordinator.reconcile()

        // Must be unfrozen
        assertFalse(gateway.hiddenPackages.contains(pkg))
        assertFalse(gateway.suspendedPackages.contains(pkg))
    }

    @Test
    fun `protected package - orphan partial restriction (hidden only) on protected is recovered`() = runTest {
        val pkg = "com.android.settings"
        gateway.installedPackages.add(pkg)
        gateway.hiddenPackages.add(pkg)
        // Suspended is false -> partial restricted state

        settingsRepository.putTarget(TargetAppConfig(pkg, 60, 0, 1439))

        coordinator.reconcile()

        assertFalse(gateway.hiddenPackages.contains(pkg))
    }

    @Test
    fun `protected package - orphan partial restriction (suspended only) on protected is recovered`() = runTest {
        val pkg = "com.android.settings"
        gateway.installedPackages.add(pkg)
        gateway.suspendedPackages.add(pkg)
        // Hidden is false -> partial restricted state

        settingsRepository.putTarget(TargetAppConfig(pkg, 60, 0, 1439))

        coordinator.reconcile()

        assertFalse(gateway.suspendedPackages.contains(pkg))
    }

    // =======================================================================
    // 3. Explicit Recovery Boundary & Prohibitions (§B2, §24, §45)
    // =======================================================================

    @Test
    fun `recovery boundary - no forbidden UserManager restrictions in codebase`() {
        val forbiddenRestrictions = listOf(
            "DISALLOW_DEBUGGING_FEATURES",
            "DISALLOW_FACTORY_RESET",
            "DISALLOW_SAFE_BOOT",
            "DISALLOW_APPS_CONTROL",
            "DISALLOW_UNINSTALL_APPS"
        )

        val srcDir = File("src/main")
        if (srcDir.exists()) {
            val allSourceFiles = srcDir.walkTopDown().filter { it.extension in listOf("kt", "java", "xml") }
            for (file in allSourceFiles) {
                val content = file.readText()
                for (forbidden in forbiddenRestrictions) {
                    assertFalse(
                        "File ${file.name} violates recovery boundary by referencing $forbidden",
                        content.contains(forbidden)
                    )
                }
            }
        }
    }

    @Test
    fun `recovery boundary - debug manifest retains testOnly for dpm remove-active-admin`() {
        val debugManifest = File("src/debug/AndroidManifest.xml")
        if (debugManifest.exists()) {
            val content = debugManifest.readText()
            assertTrue(
                "Debug manifest must retain android:testOnly=\"true\" for debug removal",
                content.contains("android:testOnly=\"true\"")
            )
        }
    }

    @Test
    fun `recovery boundary - main manifest has no internet permission`() {
        val mainManifest = File("src/main/AndroidManifest.xml")
        if (mainManifest.exists()) {
            val content = mainManifest.readText()
            assertFalse(
                "Main manifest must NOT declare android.permission.INTERNET",
                content.contains("android.permission.INTERNET")
            )
            assertFalse(
                "Main manifest must NOT declare android.permission.QUERY_ALL_PACKAGES",
                content.contains("android.permission.QUERY_ALL_PACKAGES")
            )
        }
    }
}
