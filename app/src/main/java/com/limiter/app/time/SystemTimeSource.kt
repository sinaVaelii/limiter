package com.limiter.app.time

import java.time.ZoneId

/**
 * Production [TimeSource] backed by system clock.
 */
class SystemTimeSource : TimeSource {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}
