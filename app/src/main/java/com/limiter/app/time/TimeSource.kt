package com.limiter.app.time

import java.time.ZoneId

/**
 * Abstraction over system time.
 *
 * Business logic must never call System.currentTimeMillis(),
 * Instant.now(), LocalDateTime.now(), or ZonedDateTime.now() directly.
 * Use this interface instead.
 */
interface TimeSource {
    fun nowMillis(): Long
    fun zone(): ZoneId
}
