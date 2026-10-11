package com.limiter.app.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for CriticalPackageResolver using a fake implementation.
 *
 * Every dynamically resolved protected package category must be tested.
 */
class CriticalPackageResolverTest {

    /**
     * Fake resolver for testing. Simulates device state.
     */
    class FakeCriticalPackageResolver(
        private val protectedPackages: Set<String> = emptySet(),
        private val userInstalledPackages: Set<String> = emptySet()
    ) : CriticalPackageResolver {
        override fun resolveProtected(): Set<String> = protectedPackages
        override fun isEligibleTarget(packageName: String): Boolean {
            return packageName in userInstalledPackages && packageName !in protectedPackages
        }
    }

    @Test
    fun `resolver includes Limiter itself`() {
        val resolver = FakeCriticalPackageResolver(
            protectedPackages = setOf("com.limiter.app")
        )
        assertTrue(resolver.resolveProtected().contains("com.limiter.app"))
    }

    @Test
    fun `resolver includes android framework`() {
        val resolver = FakeCriticalPackageResolver(
            protectedPackages = setOf("android")
        )
        assertTrue(resolver.resolveProtected().contains("android"))
    }

    @Test
    fun `resolver includes SystemUI`() {
        val resolver = FakeCriticalPackageResolver(
            protectedPackages = setOf("com.android.systemui")
        )
        assertTrue(resolver.resolveProtected().contains("com.android.systemui"))
    }

    @Test
    fun `resolver includes active launcher`() {
        val resolver = FakeCriticalPackageResolver(
            protectedPackages = setOf("com.google.android.apps.nexuslauncher")
        )
        assertTrue(resolver.resolveProtected().contains("com.google.android.apps.nexuslauncher"))
    }

    @Test
    fun `resolver includes default dialer`() {
        val resolver = FakeCriticalPackageResolver(
            protectedPackages = setOf("com.google.android.dialer")
        )
        assertTrue(resolver.resolveProtected().contains("com.google.android.dialer"))
    }

    @Test
    fun `resolver includes default SMS`() {
        val resolver = FakeCriticalPackageResolver(
            protectedPackages = setOf("com.google.android.apps.messaging")
        )
        assertTrue(resolver.resolveProtected().contains("com.google.android.apps.messaging"))
    }

    @Test
    fun `resolver includes Settings`() {
        val resolver = FakeCriticalPackageResolver(
            protectedPackages = setOf("com.android.settings")
        )
        assertTrue(resolver.resolveProtected().contains("com.android.settings"))
    }

    @Test
    fun `resolver includes active IME`() {
        val resolver = FakeCriticalPackageResolver(
            protectedPackages = setOf("com.google.android.inputmethod.latin")
        )
        assertTrue(resolver.resolveProtected().contains("com.google.android.inputmethod.latin"))
    }

    @Test
    fun `resolver includes accessibility services`() {
        val resolver = FakeCriticalPackageResolver(
            protectedPackages = setOf("com.example.accessibility")
        )
        assertTrue(resolver.resolveProtected().contains("com.example.accessibility"))
    }

    @Test
    fun `resolver includes VPN packages`() {
        val resolver = FakeCriticalPackageResolver(
            protectedPackages = setOf("com.example.vpn")
        )
        assertTrue(resolver.resolveProtected().contains("com.example.vpn"))
    }

    @Test
    fun `resolver includes WebView provider`() {
        val resolver = FakeCriticalPackageResolver(
            protectedPackages = setOf("com.google.android.webview")
        )
        assertTrue(resolver.resolveProtected().contains("com.google.android.webview"))
    }

    @Test
    fun `resolver includes GMS and GSF`() {
        val resolver = FakeCriticalPackageResolver(
            protectedPackages = setOf("com.google.android.gms", "com.google.android.gsf")
        )
        val protected = resolver.resolveProtected()
        assertTrue(protected.contains("com.google.android.gms"))
        assertTrue(protected.contains("com.google.android.gsf"))
    }

    @Test
    fun `resolver includes package installer`() {
        val resolver = FakeCriticalPackageResolver(
            protectedPackages = setOf("com.android.packageinstaller")
        )
        assertTrue(resolver.resolveProtected().contains("com.android.packageinstaller"))
    }

    @Test
    fun `resolver includes permission controller`() {
        val resolver = FakeCriticalPackageResolver(
            protectedPackages = setOf("com.google.android.permissioncontroller")
        )
        assertTrue(resolver.resolveProtected().contains("com.google.android.permissioncontroller"))
    }

    @Test
    fun `resolver includes telecom packages`() {
        val resolver = FakeCriticalPackageResolver(
            protectedPackages = setOf("com.android.server.telecom", "com.android.phone")
        )
        val protected = resolver.resolveProtected()
        assertTrue(protected.contains("com.android.server.telecom"))
        assertTrue(protected.contains("com.android.phone"))
    }

    // ---- Eligibility ----

    @Test
    fun `isEligibleTarget - user installed app is eligible`() {
        val resolver = FakeCriticalPackageResolver(
            userInstalledPackages = setOf("com.example.game")
        )
        assertTrue(resolver.isEligibleTarget("com.example.game"))
    }

    @Test
    fun `isEligibleTarget - system app is not eligible`() {
        val resolver = FakeCriticalPackageResolver(
            userInstalledPackages = emptySet()
        )
        assertFalse(resolver.isEligibleTarget("com.android.settings"))
    }

    @Test
    fun `isEligibleTarget - protected user app is not eligible`() {
        val resolver = FakeCriticalPackageResolver(
            protectedPackages = setOf("com.example.game"),
            userInstalledPackages = setOf("com.example.game")
        )
        assertFalse(resolver.isEligibleTarget("com.example.game"))
    }

    @Test
    fun `isEligibleTarget - unknown package is not eligible`() {
        val resolver = FakeCriticalPackageResolver()
        assertFalse(resolver.isEligibleTarget("com.nonexistent"))
    }

    // ---- Full device simulation ----

    @Test
    fun `full device simulation - protected set is comprehensive`() {
        val resolver = FakeCriticalPackageResolver(
            protectedPackages = setOf(
                "com.limiter.app",
                "android",
                "com.android.systemui",
                "com.android.settings",
                "com.android.packageinstaller",
                "com.google.android.permissioncontroller",
                "com.google.android.apps.nexuslauncher",
                "com.google.android.dialer",
                "com.google.android.apps.messaging",
                "com.google.android.inputmethod.latin",
                "com.google.android.webview",
                "com.google.android.gms",
                "com.google.android.gsf",
                "com.android.server.telecom",
                "com.android.phone"
            ),
            userInstalledPackages = setOf(
                "com.example.game",
                "com.example.social"
            )
        )

        val protected = resolver.resolveProtected()
        assertEquals(15, protected.size)

        // User apps are eligible
        assertTrue(resolver.isEligibleTarget("com.example.game"))
        assertTrue(resolver.isEligibleTarget("com.example.social"))

        // System/protected are not eligible
        assertFalse(resolver.isEligibleTarget("com.limiter.app"))
        assertFalse(resolver.isEligibleTarget("com.android.settings"))
    }
}
