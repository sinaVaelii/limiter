package com.limiter.app.policy

import com.limiter.app.model.TargetAppConfig
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Unit tests for SelfLockPolicy per R4 specification.
 */
class SelfLockPolicyTest {

    private val utc = ZoneId.of("UTC")

    private fun zdtToMillis(hour: Int, minute: Int, day: Int = 15): Long {
        return ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, utc)
            .toInstant().toEpochMilli()
    }

    @Test
    fun `isInsideWindow - same day window active within range`() {
        val config = TargetAppConfig("com.example.app", 60, 9 * 60, 17 * 60) // 09:00 - 17:00

        assertTrue(SelfLockPolicy.isInsideWindow(config, zdtToMillis(9, 0), utc))
        assertTrue(SelfLockPolicy.isInsideWindow(config, zdtToMillis(12, 30), utc))
        assertTrue(SelfLockPolicy.isInsideWindow(config, zdtToMillis(16, 59), utc))

        assertFalse(SelfLockPolicy.isInsideWindow(config, zdtToMillis(8, 59), utc))
        assertFalse(SelfLockPolicy.isInsideWindow(config, zdtToMillis(17, 0), utc))
        assertFalse(SelfLockPolicy.isInsideWindow(config, zdtToMillis(20, 0), utc))
    }

    @Test
    fun `isInsideWindow - overnight window active before and after midnight`() {
        val config = TargetAppConfig("com.example.app", 60, 23 * 60, 7 * 60) // 23:00 - 07:00

        // Before midnight
        assertTrue(SelfLockPolicy.isInsideWindow(config, zdtToMillis(23, 0), utc))
        assertTrue(SelfLockPolicy.isInsideWindow(config, zdtToMillis(23, 45), utc))

        // After midnight
        assertTrue(SelfLockPolicy.isInsideWindow(config, zdtToMillis(0, 0), utc))
        assertTrue(SelfLockPolicy.isInsideWindow(config, zdtToMillis(3, 30), utc))
        assertTrue(SelfLockPolicy.isInsideWindow(config, zdtToMillis(6, 59), utc))

        // Outside window (daytime)
        assertFalse(SelfLockPolicy.isInsideWindow(config, zdtToMillis(7, 0), utc))
        assertFalse(SelfLockPolicy.isInsideWindow(config, zdtToMillis(12, 0), utc))
        assertFalse(SelfLockPolicy.isInsideWindow(config, zdtToMillis(22, 59), utc))
    }

    @Test
    fun `calculateSelfLockStatus - no targets returns not locked`() {
        val status = SelfLockPolicy.calculateSelfLockStatus(
            targets = emptyMap(),
            nowMillis = zdtToMillis(12, 0),
            zone = utc
        )

        assertFalse(status.isSelfLocked)
        assertNull(status.unlockTimeMillis)
        assertTrue(status.activeLockedPackages.isEmpty())
    }

    @Test
    fun `calculateSelfLockStatus - outside all scheduled windows returns not locked`() {
        val targets = mapOf(
            "com.app.a" to TargetAppConfig("com.app.a", 60, 23 * 60, 7 * 60),
            "com.app.b" to TargetAppConfig("com.app.b", 30, 14 * 60, 16 * 60)
        )

        val status = SelfLockPolicy.calculateSelfLockStatus(
            targets = targets,
            nowMillis = zdtToMillis(12, 0), // noon
            zone = utc
        )

        assertFalse(status.isSelfLocked)
        assertNull(status.unlockTimeMillis)
    }

    @Test
    fun `calculateSelfLockStatus - single active overnight window before midnight`() {
        val targets = mapOf(
            "com.app.a" to TargetAppConfig("com.app.a", 60, 23 * 60, 7 * 60)
        )

        val now = zdtToMillis(23, 30, day = 15)
        val status = SelfLockPolicy.calculateSelfLockStatus(
            targets = targets,
            nowMillis = now,
            zone = utc
        )

        assertTrue(status.isSelfLocked)
        assertEquals(listOf("com.app.a"), status.activeLockedPackages)
        // Expected reveal time is 07:00 on day 16
        val expectedUnlock = zdtToMillis(7, 0, day = 16)
        assertEquals(expectedUnlock, status.unlockTimeMillis)
    }

    @Test
    fun `calculateSelfLockStatus - single active overnight window after midnight`() {
        val targets = mapOf(
            "com.app.a" to TargetAppConfig("com.app.a", 60, 23 * 60, 7 * 60)
        )

        val now = zdtToMillis(2, 0, day = 16)
        val status = SelfLockPolicy.calculateSelfLockStatus(
            targets = targets,
            nowMillis = now,
            zone = utc
        )

        assertTrue(status.isSelfLocked)
        val expectedUnlock = zdtToMillis(7, 0, day = 16)
        assertEquals(expectedUnlock, status.unlockTimeMillis)
    }

    @Test
    fun `calculateSelfLockStatus - multiple targets with overlapping windows selects latest reveal`() {
        val targets = mapOf(
            "com.app.a" to TargetAppConfig("com.app.a", 60, 22 * 60, 6 * 60), // ends 06:00
            "com.app.b" to TargetAppConfig("com.app.b", 60, 23 * 60, 8 * 60)  // ends 08:00
        )

        val now = zdtToMillis(23, 30, day = 15) // both are active
        val status = SelfLockPolicy.calculateSelfLockStatus(
            targets = targets,
            nowMillis = now,
            zone = utc
        )

        assertTrue(status.isSelfLocked)
        assertEquals(2, status.activeLockedPackages.size)
        // Latest reveal is 08:00 on day 16
        val expectedLatestUnlock = zdtToMillis(8, 0, day = 16)
        assertEquals(expectedLatestUnlock, status.unlockTimeMillis)
    }

    @Test
    fun `calculateSelfLockStatus - protected packages are excluded from self-lock calculation`() {
        val targets = mapOf(
            "com.android.settings" to TargetAppConfig("com.android.settings", 60, 23 * 60, 7 * 60)
        )

        val now = zdtToMillis(23, 30)
        val status = SelfLockPolicy.calculateSelfLockStatus(
            targets = targets,
            nowMillis = now,
            zone = utc,
            protectedPackages = setOf("com.android.settings")
        )

        assertFalse(status.isSelfLocked)
        assertNull(status.unlockTimeMillis)
    }

    @Test
    fun `formatRemainingTime - correctly formats millis into HH_mm_ss`() {
        assertEquals("00:00:00", SelfLockPolicy.formatRemainingTime(0L))
        assertEquals("00:00:00", SelfLockPolicy.formatRemainingTime(-500L))
        assertEquals("00:00:05", SelfLockPolicy.formatRemainingTime(5000L))
        assertEquals("00:01:05", SelfLockPolicy.formatRemainingTime(65000L))
        assertEquals("01:01:05", SelfLockPolicy.formatRemainingTime(3665000L))
        assertEquals("07:30:00", SelfLockPolicy.formatRemainingTime(27000000L))
    }
}
