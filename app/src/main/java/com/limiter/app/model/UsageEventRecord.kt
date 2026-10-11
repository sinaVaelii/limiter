package com.limiter.app.model

/**
 * Internal representation of a usage event.
 *
 * Maps from Android's UsageEvents.Event to a pure-Kotlin record
 * that the UsageCalculator can process without Android dependencies.
 */
data class UsageEventRecord(
    val packageName: String,
    val className: String?,
    val timestampMillis: Long,
    val eventType: EventType
) {
    enum class EventType {
        ACTIVITY_RESUMED,
        ACTIVITY_PAUSED,
        ACTIVITY_STOPPED,
        DEVICE_SHUTDOWN
    }
}
