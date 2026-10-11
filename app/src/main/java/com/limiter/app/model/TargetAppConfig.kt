package com.limiter.app.model

/**
 * Configuration for a single target application.
 *
 * Times of day are Int minuteOfDay in [0, 1439].
 */
data class TargetAppConfig(
    val packageName: String,
    val dailyLimitMinutes: Int,
    val hideAtMinuteOfDay: Int,
    val revealAtMinuteOfDay: Int
) {
    init {
        require(dailyLimitMinutes in 1..1440) {
            "dailyLimitMinutes must be in 1..1440, was $dailyLimitMinutes"
        }
        require(hideAtMinuteOfDay in 0..1439) {
            "hideAtMinuteOfDay must be in 0..1439, was $hideAtMinuteOfDay"
        }
        require(revealAtMinuteOfDay in 0..1439) {
            "revealAtMinuteOfDay must be in 0..1439, was $revealAtMinuteOfDay"
        }
        require(hideAtMinuteOfDay != revealAtMinuteOfDay) {
            "hideAtMinuteOfDay ($hideAtMinuteOfDay) must differ from revealAtMinuteOfDay ($revealAtMinuteOfDay)"
        }
    }
}
