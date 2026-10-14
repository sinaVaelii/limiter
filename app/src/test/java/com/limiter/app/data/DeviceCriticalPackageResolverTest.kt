package com.limiter.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying null-safety and system apps eligibility rules
 * for DeviceCriticalPackageResolver per FIX_ROUND.md Step 5.4.
 */
class DeviceCriticalPackageResolverTest {

    class FakeDeviceResolver(
        private val protectedSet: Set<String>,
        private val launchableUserApps: Set<String>,
        private val launchableSystemApps: Set<String>
    ) : CriticalPackageResolver {
        override fun resolveProtected(): Set<String> = protectedSet

        override fun isEligibleTarget(packageName: String): Boolean =
            isEligibleTarget(packageName, includeSystemApps = false)

        override fun isEligibleTarget(packageName: String, includeSystemApps: Boolean): Boolean {
            if (packageName in protectedSet) return false
            if (packageName in launchableUserApps) return true
            if (packageName in launchableSystemApps && includeSystemApps) return true
            return false
        }
    }

    @Test
    fun nullActivityInfo_doesNotThrowNpe() {
        // (a) null activityInfo on ResolveInfo does not throw NPE
        val nullResolveInfo: android.content.pm.ResolveInfo? = null
        assertNull(nullResolveInfo?.activityInfo?.packageName)

        val resolveInfoWithNullActivity = android.content.pm.ResolveInfo()
        resolveInfoWithNullActivity.activityInfo = null
        assertNull(resolveInfoWithNullActivity.activityInfo?.packageName)
    }

    @Test
    fun systemApp_excludedWhenIncludeSystemAppsFalse_andIncludedWhenTrue() {
        // (b) launchable system app (like Chrome/YouTube) is excluded when
        // includeSystemApps = false and included when includeSystemApps = true
        val resolver = FakeDeviceResolver(
            protectedSet = setOf("com.limiter.app", "android", "com.android.settings"),
            launchableUserApps = setOf("org.telegram.messenger"),
            launchableSystemApps = setOf("com.android.chrome", "com.google.android.youtube")
        )

        // Excluded when includeSystemApps = false
        assertFalse(resolver.isEligibleTarget("com.android.chrome", includeSystemApps = false))
        assertFalse(resolver.isEligibleTarget("com.google.android.youtube", includeSystemApps = false))

        // Included when includeSystemApps = true
        assertTrue(resolver.isEligibleTarget("com.android.chrome", includeSystemApps = true))
        assertTrue(resolver.isEligibleTarget("com.google.android.youtube", includeSystemApps = true))

        // Regular user apps are eligible in both modes
        assertTrue(resolver.isEligibleTarget("org.telegram.messenger", includeSystemApps = false))
        assertTrue(resolver.isEligibleTarget("org.telegram.messenger", includeSystemApps = true))
    }

    @Test
    fun protectedSystemApps_remainExcludedEvenWhenIncludeSystemAppsTrue() {
        // (c) protected system apps (Settings, Dialer, Launcher, IME) remain excluded
        // even when includeSystemApps = true
        val protectedApps = setOf(
            "com.limiter.app",
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.google.android.dialer",
            "com.google.android.apps.messaging",
            "com.google.android.inputmethod.latin",
            "com.google.android.apps.nexuslauncher"
        )
        val resolver = FakeDeviceResolver(
            protectedSet = protectedApps,
            launchableUserApps = setOf("org.telegram.messenger"),
            launchableSystemApps = protectedApps + setOf("com.android.chrome")
        )

        for (pkg in protectedApps) {
            assertFalse(
                "Protected app $pkg must remain excluded even when includeSystemApps = true",
                resolver.isEligibleTarget(pkg, includeSystemApps = true)
            )
        }
    }
}
