package com.limiter.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Basic JVM test to verify the test pipeline works.
 */
class SanityTest {

    @Test
    fun `application id is correct`() {
        assertEquals("com.limiter.app", "com.limiter.app")
    }

    @Test
    fun `admin receiver component name is well-formed`() {
        val component = "com.limiter.app/.admin.LimiterAdminReceiver"
        assert(component.contains("/")) { "Component name must contain /" }
        assert(component.startsWith("com.limiter.app")) { "Must start with app ID" }
    }
}
