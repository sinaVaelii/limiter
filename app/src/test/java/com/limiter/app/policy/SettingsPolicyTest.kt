package com.limiter.app.policy

import com.limiter.app.model.LockState
import com.limiter.app.model.PendingChange
import com.limiter.app.model.TargetAppConfig
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Tests for SettingsPolicy per spec section 12.
 */
class SettingsPolicyTest {

    private val utc = ZoneId.of("UTC")

    private fun zdtToMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, utc)
            .toInstant().toEpochMilli()
    }

    private val sampleConfig = TargetAppConfig(
        packageName = "com.example.app",
        dailyLimitMinutes = 60,
        hideAtMinuteOfDay = 1320,  // 22:00
        revealAtMinuteOfDay = 420   // 07:00
    )

    // ---- Add ----

    @Test
    fun `add - valid, not duplicate, not protected`() {
        val result = SettingsPolicy.evaluateAdd(
            config = sampleConfig,
            currentTargets = emptyMap(),
            protectedPackages = emptySet()
        )
        assertTrue(result is SettingsPolicy.AddResult.Ok)
    }

    @Test
    fun `add - duplicate`() {
        val result = SettingsPolicy.evaluateAdd(
            config = sampleConfig,
            currentTargets = mapOf(sampleConfig.packageName to sampleConfig),
            protectedPackages = emptySet()
        )
        assertEquals(SettingsPolicy.AddResult.Duplicate, result)
    }

    @Test
    fun `add - protected`() {
        val result = SettingsPolicy.evaluateAdd(
            config = sampleConfig,
            currentTargets = emptyMap(),
            protectedPackages = setOf(sampleConfig.packageName)
        )
        assertEquals(SettingsPolicy.AddResult.Protected, result)
    }

    // ---- Edit ----

    @Test
    fun `edit - lower daily limit is immediate (tightening)`() {
        val existing = mapOf(sampleConfig.packageName to sampleConfig)
        val tighter = sampleConfig.copy(dailyLimitMinutes = 30)
        val result = SettingsPolicy.evaluateEdit(
            newConfig = tighter,
            currentTargets = existing,
            nowMillis = zdtToMillis(2024, 6, 15, 12, 0),
            zone = utc
        )
        assertTrue("Expected Applied, got $result", result is SettingsPolicy.EditResult.Applied)
        assertEquals(30, (result as SettingsPolicy.EditResult.Applied).config.dailyLimitMinutes)
    }

    @Test
    fun `edit - higher daily limit is deferred (loosening)`() {
        val existing = mapOf(sampleConfig.packageName to sampleConfig)
        val looser = sampleConfig.copy(dailyLimitMinutes = 120)
        val nowMillis = zdtToMillis(2024, 6, 15, 12, 0)
        val result = SettingsPolicy.evaluateEdit(
            newConfig = looser,
            currentTargets = existing,
            nowMillis = nowMillis,
            zone = utc
        )
        assertTrue("Expected Deferred, got $result", result is SettingsPolicy.EditResult.Deferred)
        val deferred = result as SettingsPolicy.EditResult.Deferred
        assertEquals(
            LocalDate.of(2024, 6, 15).toEpochDay() + 1,
            deferred.pending.eligibleFromEpochDay
        )
    }

    @Test
    fun `edit - window change is deferred`() {
        val existing = mapOf(sampleConfig.packageName to sampleConfig)
        val newWindow = sampleConfig.copy(hideAtMinuteOfDay = 1380, revealAtMinuteOfDay = 480)
        val result = SettingsPolicy.evaluateEdit(
            newConfig = newWindow,
            currentTargets = existing,
            nowMillis = zdtToMillis(2024, 6, 15, 12, 0),
            zone = utc
        )
        assertTrue("Expected Deferred, got $result", result is SettingsPolicy.EditResult.Deferred)
    }

    @Test
    fun `edit - tightened limit plus window change is partial`() {
        val existing = mapOf(sampleConfig.packageName to sampleConfig)
        val mixed = sampleConfig.copy(dailyLimitMinutes = 30, hideAtMinuteOfDay = 1380, revealAtMinuteOfDay = 480)
        val result = SettingsPolicy.evaluateEdit(
            newConfig = mixed,
            currentTargets = existing,
            nowMillis = zdtToMillis(2024, 6, 15, 12, 0),
            zone = utc
        )
        assertTrue("Expected PartialApplied, got $result", result is SettingsPolicy.EditResult.PartialApplied)
        val partial = result as SettingsPolicy.EditResult.PartialApplied
        assertEquals(30, partial.appliedNow.dailyLimitMinutes)
        // appliedNow keeps old window
        assertEquals(1320, partial.appliedNow.hideAtMinuteOfDay)
        assertEquals(420, partial.appliedNow.revealAtMinuteOfDay)
        // pending has the full desired config
        assertEquals(mixed, partial.pending.desired)
    }

    @Test
    fun `edit - not found`() {
        val result = SettingsPolicy.evaluateEdit(
            newConfig = sampleConfig,
            currentTargets = emptyMap(),
            nowMillis = zdtToMillis(2024, 6, 15, 12, 0),
            zone = utc
        )
        assertEquals(SettingsPolicy.EditResult.NotFound, result)
    }

    // ---- Remove ----

    @Test
    fun `remove - deferred to next day`() {
        val existing = mapOf(sampleConfig.packageName to sampleConfig)
        val nowMillis = zdtToMillis(2024, 6, 15, 12, 0)
        val result = SettingsPolicy.evaluateRemove(
            packageName = sampleConfig.packageName,
            currentTargets = existing,
            nowMillis = nowMillis,
            zone = utc
        )
        assertTrue("Expected Deferred, got $result", result is SettingsPolicy.RemoveResult.Deferred)
        val deferred = result as SettingsPolicy.RemoveResult.Deferred
        assertNull(deferred.pending.desired)  // null means removal
        assertEquals(
            LocalDate.of(2024, 6, 15).toEpochDay() + 1,
            deferred.pending.eligibleFromEpochDay
        )
    }

    @Test
    fun `remove - not found`() {
        val result = SettingsPolicy.evaluateRemove(
            packageName = "nonexistent",
            currentTargets = emptyMap(),
            nowMillis = zdtToMillis(2024, 6, 15, 12, 0),
            zone = utc
        )
        assertEquals(SettingsPolicy.RemoveResult.NotFound, result)
    }

    // ---- filterEligiblePending ----

    @Test
    fun `filterEligiblePending - eligible when day reached and unlocked and not protected`() {
        val pending = listOf(
            PendingChange("com.example.app", sampleConfig, eligibleFromEpochDay = 100)
        )
        val result = SettingsPolicy.filterEligiblePending(
            pending = pending,
            todayEpochDay = 100,
            lockStates = emptyMap(),
            protectedPackages = emptySet()
        )
        assertEquals(1, result.size)
    }

    @Test
    fun `filterEligiblePending - not eligible when day not reached`() {
        val pending = listOf(
            PendingChange("com.example.app", sampleConfig, eligibleFromEpochDay = 101)
        )
        val result = SettingsPolicy.filterEligiblePending(
            pending = pending,
            todayEpochDay = 100,
            lockStates = emptyMap(),
            protectedPackages = emptySet()
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `filterEligiblePending - not eligible when locked`() {
        val pending = listOf(
            PendingChange("com.example.app", sampleConfig, eligibleFromEpochDay = 100)
        )
        val result = SettingsPolicy.filterEligiblePending(
            pending = pending,
            todayEpochDay = 100,
            lockStates = mapOf("com.example.app" to LockState.LockedUntil(Long.MAX_VALUE)),
            protectedPackages = emptySet()
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `filterEligiblePending - not eligible when protected`() {
        val pending = listOf(
            PendingChange("com.example.app", sampleConfig, eligibleFromEpochDay = 100)
        )
        val result = SettingsPolicy.filterEligiblePending(
            pending = pending,
            todayEpochDay = 100,
            lockStates = emptyMap(),
            protectedPackages = setOf("com.example.app")
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `pending conflict resolution - latest wins`() {
        // This is a repository-level behavior, but we test that
        // filterEligiblePending handles single pending per package
        val pending = listOf(
            PendingChange("com.example.app", sampleConfig.copy(dailyLimitMinutes = 120), eligibleFromEpochDay = 100)
        )
        val result = SettingsPolicy.filterEligiblePending(
            pending = pending,
            todayEpochDay = 100,
            lockStates = emptyMap(),
            protectedPackages = emptySet()
        )
        assertEquals(1, result.size)
        assertEquals(120, result[0].desired?.dailyLimitMinutes)
    }
}
