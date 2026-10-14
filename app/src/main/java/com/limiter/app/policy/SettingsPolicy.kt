package com.limiter.app.policy

import com.limiter.app.model.LockState
import com.limiter.app.model.PendingChange
import com.limiter.app.model.TargetAppConfig
import com.limiter.app.time.TimeMath
import java.time.ZoneId

/**
 * Pure policy logic for settings changes per spec section 12.
 *
 * No Android dependencies. Fully testable with fake inputs.
 */
object SettingsPolicy {

    const val MAX_DAILY_LIMIT_MINUTES = 720
    const val MAX_LIMIT_INCREASE_MINUTES = 30
    const val MIN_DAILY_LIMIT_MINUTES = 1

    sealed interface EditResult {
        /** Change was applied immediately. */
        data class Applied(val config: TargetAppConfig) : EditResult

        /** Change was deferred to a future date. */
        data class Deferred(val pending: PendingChange) : EditResult

        /** Partial: tightening applied now, loosening deferred. */
        data class PartialApplied(
            val appliedNow: TargetAppConfig,
            val pending: PendingChange
        ) : EditResult

        /** Target was not found. */
        data object NotFound : EditResult

        /** Package is protected. */
        data object Protected : EditResult

        /** Duplicate — already exists. */
        data object Duplicate : EditResult

        /** Invalid configuration. */
        data class Invalid(val reason: String) : EditResult

        /** Management controls locked during active restriction window. */
        data object SelfLocked : EditResult
    }

    sealed interface AddResult {
        data class Ok(val config: TargetAppConfig) : AddResult
        data object Duplicate : AddResult
        data object Protected : AddResult
        data class Invalid(val reason: String) : AddResult
        data object SelfLocked : AddResult
    }

    sealed interface RemoveResult {
        data class Deferred(val pending: PendingChange) : RemoveResult
        data object NotFound : RemoveResult
        data object SelfLocked : RemoveResult
    }

    /**
     * Evaluate adding a new target per Rule A.
     *
     * Limit must be in 1..720 (MIN_DAILY_LIMIT_MINUTES..MAX_DAILY_LIMIT_MINUTES).
     * Immediate when: valid, not duplicate, not protected.
     */
    fun evaluateAdd(
        config: TargetAppConfig,
        currentTargets: Map<String, TargetAppConfig>,
        protectedPackages: Set<String>
    ): AddResult {
        if (!isValid(config)) {
            return AddResult.Invalid(
                if (config.dailyLimitMinutes > MAX_DAILY_LIMIT_MINUTES) {
                    "Daily limit cannot exceed $MAX_DAILY_LIMIT_MINUTES minutes"
                } else if (config.dailyLimitMinutes < MIN_DAILY_LIMIT_MINUTES) {
                    "Daily limit must be at least $MIN_DAILY_LIMIT_MINUTES minute"
                } else {
                    "Configuration validation failed"
                }
            )
        }
        if (config.packageName in currentTargets) {
            return AddResult.Duplicate
        }
        if (config.packageName in protectedPackages) {
            return AddResult.Protected
        }
        return AddResult.Ok(config)
    }

    /**
     * Evaluate editing an existing target per Rule A.
     *
     * - Lower daily limit: apply immediately (tightening).
     * - Increase by d = proposed - currentActiveLimit:
     *   - If d > 30: rejected with specific error.
     *   - If proposed > 720: rejected.
     *   - If 0 < d <= 30: deferred to next day (loosening).
     *   - Cumulative cap: compares against currently active limit (effectiveActiveLimit),
     *     never against pending, so queuing cannot exceed +30 total.
     * - Hide/reveal changes: defer by default.
     * - Mixed tightening + loosening: apply tightening now, defer loosening.
     */
    fun evaluateEdit(
        newConfig: TargetAppConfig,
        currentTargets: Map<String, TargetAppConfig>,
        nowMillis: Long,
        zone: ZoneId
    ): EditResult {
        val existing = currentTargets[newConfig.packageName]
            ?: return EditResult.NotFound

        if (!isValid(newConfig)) {
            return EditResult.Invalid(
                if (newConfig.dailyLimitMinutes > MAX_DAILY_LIMIT_MINUTES) {
                    "Daily limit cannot exceed $MAX_DAILY_LIMIT_MINUTES minutes"
                } else if (newConfig.dailyLimitMinutes < MIN_DAILY_LIMIT_MINUTES) {
                    "Daily limit must be at least $MIN_DAILY_LIMIT_MINUTES minute"
                } else {
                    "Configuration validation failed"
                }
            )
        }

        val effectiveActiveLimit = minOf(existing.dailyLimitMinutes, MAX_DAILY_LIMIT_MINUTES)
        val todayEpochDay = TimeMath.localEpochDay(nowMillis, zone)
        val limitChanged = newConfig.dailyLimitMinutes != existing.dailyLimitMinutes
        val windowChanged = newConfig.hideAtMinuteOfDay != existing.hideAtMinuteOfDay ||
                newConfig.revealAtMinuteOfDay != existing.revealAtMinuteOfDay

        // Rule A: Check limit increase against currently active limit
        if (newConfig.dailyLimitMinutes > effectiveActiveLimit) {
            val increase = newConfig.dailyLimitMinutes - effectiveActiveLimit
            if (increase > MAX_LIMIT_INCREASE_MINUTES) {
                return EditResult.Invalid("Daily limit can be increased by at most $MAX_LIMIT_INCREASE_MINUTES minutes per change")
            }
        }

        // Pure tightening: lower limit, no window change
        if (limitChanged && !windowChanged && newConfig.dailyLimitMinutes < existing.dailyLimitMinutes) {
            return EditResult.Applied(newConfig)
        }

        // Pure loosening: higher limit, no window change
        if (limitChanged && !windowChanged && newConfig.dailyLimitMinutes > existing.dailyLimitMinutes) {
            return EditResult.Deferred(
                PendingChange(
                    packageName = newConfig.packageName,
                    desired = newConfig,
                    eligibleFromEpochDay = todayEpochDay + 1
                )
            )
        }

        // Window changed (with or without limit change)
        if (windowChanged) {
            // If limit also tightened, apply the tightened limit now,
            // defer the complete desired config
            if (limitChanged && newConfig.dailyLimitMinutes < existing.dailyLimitMinutes) {
                val appliedNow = existing.copy(dailyLimitMinutes = newConfig.dailyLimitMinutes)
                return EditResult.PartialApplied(
                    appliedNow = appliedNow,
                    pending = PendingChange(
                        packageName = newConfig.packageName,
                        desired = newConfig,
                        eligibleFromEpochDay = todayEpochDay + 1
                    )
                )
            }

            // All other window changes defer
            return EditResult.Deferred(
                PendingChange(
                    packageName = newConfig.packageName,
                    desired = newConfig,
                    eligibleFromEpochDay = todayEpochDay + 1
                )
            )
        }

        // No actual change
        return EditResult.Applied(newConfig)
    }

    /**
     * Evaluate removing a target.
     *
     * Removal is always deferred: eligibleFromEpochDay = today + 1.
     */
    fun evaluateRemove(
        packageName: String,
        currentTargets: Map<String, TargetAppConfig>,
        nowMillis: Long,
        zone: ZoneId
    ): RemoveResult {
        if (packageName !in currentTargets) {
            return RemoveResult.NotFound
        }
        val todayEpochDay = TimeMath.localEpochDay(nowMillis, zone)
        return RemoveResult.Deferred(
            PendingChange(
                packageName = packageName,
                desired = null,  // null means removal
                eligibleFromEpochDay = todayEpochDay + 1
            )
        )
    }

    /**
     * Determine which pending changes are eligible to apply.
     *
     * A pending change is applied when:
     * - todayEpochDay >= eligibleFromEpochDay
     * - package is unlocked
     * - package is not protected
     */
    fun filterEligiblePending(
        pending: List<PendingChange>,
        todayEpochDay: Long,
        lockStates: Map<String, LockState>,
        protectedPackages: Set<String>
    ): List<PendingChange> {
        return pending.filter { change ->
            todayEpochDay >= change.eligibleFromEpochDay &&
                    change.packageName !in protectedPackages &&
                    (lockStates[change.packageName] ?: LockState.Unlocked) is LockState.Unlocked
        }
    }

    fun isValid(config: TargetAppConfig): Boolean {
        return try {
            config.dailyLimitMinutes in MIN_DAILY_LIMIT_MINUTES..MAX_DAILY_LIMIT_MINUTES &&
                    config.hideAtMinuteOfDay in 0..1439 &&
                    config.revealAtMinuteOfDay in 0..1439 &&
                    config.hideAtMinuteOfDay != config.revealAtMinuteOfDay
        } catch (_: Exception) {
            false
        }
    }

    fun windowDurationMinutes(hide: Int, reveal: Int): Int {
        return if (hide < reveal) {
            reveal - hide
        } else {
            (1440 - hide) + reveal
        }
    }

    fun isWindowLoosening(oldHide: Int, oldReveal: Int, newHide: Int, newReveal: Int): Boolean {
        if (oldHide == newHide && oldReveal == newReveal) return false
        val oldDuration = windowDurationMinutes(oldHide, oldReveal)
        val newDuration = windowDurationMinutes(newHide, newReveal)
        return newDuration < oldDuration
    }
}
