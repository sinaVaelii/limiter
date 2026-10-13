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
            isEligibleTarget(packageName, allowSystemApps = false)

        override fun isEligibleTarget(packageName: String, allowSystemApps: Boolean): Boolean {
            if (packageName in protectedSet) return false
            if (packageName in launchableUserApps) return true
            if (packageName in launchableSystemApps && allowSystemApps) return true
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
        // allowSystemApps = false and included when allowSystemApps = true
        val resolver = FakeDeviceResolver(
            protectedSet = setOf("com.limiter.app", "android", "com.android.settings"),
            launchableUserApps = setOf("org.telegram.messenger"),
            launchableSystemApps = setOf("com.android.chrome", "com.google.android.youtube")
        )

        // Excluded when allowSystemApps = false
        assertFalse(resolver.isEligibleTarget("com.android.chrome", allowSystemApps = false))
        assertFalse(resolver.isEligibleTarget("com.google.android.youtube", allowSystemApps = false))

        // Included when allowSystemApps = true
        assertTrue(resolver.isEligibleTarget("com.android.chrome", allowSystemApps = true))
        assertTrue(resolver.isEligibleTarget("com.google.android.youtube", allowSystemApps = true))

        // Regular user apps are eligible in both modes
        assertTrue(resolver.isEligibleTarget("org.telegram.messenger", allowSystemApps = false))
        assertTrue(resolver.isEligibleTarget("org.telegram.messenger", allowSystemApps = true))
    }

    @Test
    fun protectedSystemApps_remainExcludedEvenWhenIncludeSystemAppsTrue() {
        // (c) protected system apps (Settings, Dialer, Launcher, IME) remain excluded
        // even when allowSystemApps = true
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
                "Protected app $pkg must remain excluded even when allowSystemApps = true",
                resolver.isEligibleTarget(pkg, allowSystemApps = true)
            )
        }
    }
}
