package com.limiter.app.time

import java.time.ZoneId

/**
 * Fake [TimeSource] for unit tests.
 *
 * Allows setting exact time and zone for deterministic testing.
 */
class FakeTimeSource(
    private var currentMillis: Long = 0L,
    private var currentZone: ZoneId = ZoneId.of("UTC")
) : TimeSource {

    override fun nowMillis(): Long = currentMillis
    override fun zone(): ZoneId = currentZone

    fun setNowMillis(millis: Long) {
        currentMillis = millis
    }

    fun setZone(zone: ZoneId) {
        currentZone = zone
    }

    fun advanceBy(millis: Long) {
        currentMillis += millis
    }
}
