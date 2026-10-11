package com.limiter.app.data

import com.limiter.app.model.PendingChange
import com.limiter.app.model.TargetAppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codec round-trip tests per spec section 19.
 */
class CodecTest {

    private val sampleConfig = TargetAppConfig(
        packageName = "com.example.app",
        dailyLimitMinutes = 60,
        hideAtMinuteOfDay = 1320,
        revealAtMinuteOfDay = 420
    )

    // ---- TargetAppConfig round-trip ----

    @Test
    fun `targets round-trip - single config`() {
        val original = mapOf(sampleConfig.packageName to sampleConfig)
        val encoded = Codec.encodeTargets(original)
        val decoded = Codec.decodeTargets(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `targets round-trip - multiple configs`() {
        val config2 = TargetAppConfig("com.example.two", 120, 1380, 480)
        val original = mapOf(
            sampleConfig.packageName to sampleConfig,
            config2.packageName to config2
        )
        val encoded = Codec.encodeTargets(original)
        val decoded = Codec.decodeTargets(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `targets round-trip - empty`() {
        val original = emptyMap<String, TargetAppConfig>()
        val encoded = Codec.encodeTargets(original)
        val decoded = Codec.decodeTargets(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `targets round-trip - boundary values`() {
        val config = TargetAppConfig("com.test", 1, 0, 1439)
        val original = mapOf(config.packageName to config)
        val encoded = Codec.encodeTargets(original)
        val decoded = Codec.decodeTargets(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `decodeTargets - malformed data is ignored`() {
        val raw = "com.bad.format|60|1320\ncom.good.format|60|1320|420"
        val decoded = Codec.decodeTargets(raw)
        assertEquals(1, decoded.size)
        assertTrue(decoded.containsKey("com.good.format"))
    }

    // ---- PendingChange round-trip ----

    @Test
    fun `pending round-trip - with desired config`() {
        val original = listOf(
            PendingChange("com.example.app", sampleConfig, 19889)
        )
        val encoded = Codec.encodePending(original)
        val decoded = Codec.decodePending(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `pending round-trip - null desired (removal)`() {
        val original = listOf(
            PendingChange("com.example.app", null, 19889)
        )
        val encoded = Codec.encodePending(original)
        val decoded = Codec.decodePending(encoded)
        assertEquals(1, decoded.size)
        assertEquals("com.example.app", decoded[0].packageName)
        assertNull(decoded[0].desired)
        assertEquals(19889L, decoded[0].eligibleFromEpochDay)
    }

    @Test
    fun `pending round-trip - multiple pending changes`() {
        val original = listOf(
            PendingChange("com.example.app", sampleConfig, 19889),
            PendingChange("com.example.two", null, 19890)
        )
        val encoded = Codec.encodePending(original)
        val decoded = Codec.decodePending(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `pending round-trip - empty`() {
        val original = emptyList<PendingChange>()
        val encoded = Codec.encodePending(original)
        val decoded = Codec.decodePending(encoded)
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun `decodePending - malformed data is ignored`() {
        val raw = "com.bad.format~null\ncom.good.format~null~12345"
        val decoded = Codec.decodePending(raw)
        assertEquals(1, decoded.size)
        assertEquals("com.good.format", decoded[0].packageName)
    }
}
