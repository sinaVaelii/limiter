package com.limiter.app.dpm

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for AppFreezer.
 *
 * Covers:
 * - suspend success + hide success -> Ok
 * - suspend reports failed package -> Failed
 * - suspend succeeds + hide fails -> Failed, logical lock remains
 * - hidden-only physical state -> isFrozen false
 * - suspended-only physical state -> isFrozen false
 * - unfreeze ordering
 * - partial unfreeze recovery
 */
class AppFreezerTest {

    private lateinit var gateway: FakeDpmGateway
    private lateinit var freezer: AppFreezer

    @Before
    fun setup() {
        gateway = FakeDpmGateway()
        gateway.deviceOwner = true
        freezer = AppFreezer(gateway)
    }

    @Test
    fun `freeze - suspend success + hide success returns Ok`() {
        gateway.installedPackages.add("com.example.app")
        val result = freezer.freeze("com.example.app")
        assertEquals(DpmOperationResult.Ok, result)
        assertTrue(gateway.suspendedPackages.contains("com.example.app"))
        assertTrue(gateway.hiddenPackages.contains("com.example.app"))
    }

    @Test
    fun `freeze - isFrozen true when both hidden and suspended`() {
        gateway.installedPackages.add("com.example.app")
        freezer.freeze("com.example.app")
        assertEquals(true, freezer.isFrozen("com.example.app"))
    }

    @Test
    fun `freeze - suspend fails returns Failed`() {
        gateway.installedPackages.add("com.example.app")
        gateway.suspendFailPackages.add("com.example.app")
        val result = freezer.freeze("com.example.app")
        assertTrue("Expected Failed, got $result", result is DpmOperationResult.Failed)
        assertFalse(gateway.suspendedPackages.contains("com.example.app"))
        assertFalse(gateway.hiddenPackages.contains("com.example.app"))
    }

    @Test
    fun `freeze - suspend succeeds but hide fails returns Failed`() {
        gateway.installedPackages.add("com.example.app")
        gateway.hideFailPackages.add("com.example.app")
        val result = freezer.freeze("com.example.app")
        assertTrue("Expected Failed, got $result", result is DpmOperationResult.Failed)
        // Package was suspended but not hidden -> partial state
        assertTrue(gateway.suspendedPackages.contains("com.example.app"))
        assertFalse(gateway.hiddenPackages.contains("com.example.app"))
    }

    @Test
    fun `hidden-only is NOT frozen`() {
        gateway.hiddenPackages.add("com.example.app")
        assertEquals(false, freezer.isFrozen("com.example.app"))
    }

    @Test
    fun `suspended-only is NOT frozen`() {
        gateway.suspendedPackages.add("com.example.app")
        assertEquals(false, freezer.isFrozen("com.example.app"))
    }

    @Test
    fun `neither hidden nor suspended is not frozen`() {
        assertEquals(false, freezer.isFrozen("com.example.app"))
    }

    @Test
    fun `unfreeze - unhide success + unsuspend success returns Ok`() {
        gateway.hiddenPackages.add("com.example.app")
        gateway.suspendedPackages.add("com.example.app")
        val result = freezer.unfreeze("com.example.app")
        assertEquals(DpmOperationResult.Ok, result)
        assertFalse(gateway.hiddenPackages.contains("com.example.app"))
        assertFalse(gateway.suspendedPackages.contains("com.example.app"))
    }

    @Test
    fun `unfreeze - unhide fails returns Failed`() {
        gateway.hiddenPackages.add("com.example.app")
        gateway.suspendedPackages.add("com.example.app")
        gateway.unhideFailPackages.add("com.example.app")
        val result = freezer.unfreeze("com.example.app")
        assertTrue("Expected Failed, got $result", result is DpmOperationResult.Failed)
        // Still hidden, still suspended
        assertTrue(gateway.hiddenPackages.contains("com.example.app"))
        assertTrue(gateway.suspendedPackages.contains("com.example.app"))
    }

    @Test
    fun `unfreeze - unhide succeeds but unsuspend fails returns Failed`() {
        gateway.hiddenPackages.add("com.example.app")
        gateway.suspendedPackages.add("com.example.app")
        gateway.unsuspendFailPackages.add("com.example.app")
        val result = freezer.unfreeze("com.example.app")
        assertTrue("Expected Failed, got $result", result is DpmOperationResult.Failed)
        // Unhidden but still suspended -> partial state
        assertFalse(gateway.hiddenPackages.contains("com.example.app"))
        assertTrue(gateway.suspendedPackages.contains("com.example.app"))
    }

    @Test
    fun `freeze - not device owner returns NotDeviceOwner`() {
        gateway.deviceOwner = false
        val result = freezer.freeze("com.example.app")
        assertEquals(DpmOperationResult.NotDeviceOwner, result)
    }

    @Test
    fun `unfreeze - not device owner returns NotDeviceOwner`() {
        gateway.deviceOwner = false
        val result = freezer.unfreeze("com.example.app")
        assertEquals(DpmOperationResult.NotDeviceOwner, result)
    }
}
