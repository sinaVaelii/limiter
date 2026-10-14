package com.limiter.app.policy

import com.limiter.app.model.TargetAppConfig
import com.limiter.app.time.TimeMath
import java.time.Instant
import java.time.ZoneId

/**
 * Pure policy logic for UI self-lock and unlock deadlines per R4 specification.
 *
 * Rule 1: The management UI becomes self-locked when at least one eligible configured
 * target is currently inside its scheduled hide/reveal restriction window.
 *
 * Rule 2: The unlock deadline is the latest reveal time among all currently active
 * scheduled windows (max of active window end times).
 *
 * Rule 3: If another configured target enters its restricted window while the countdown
 * is running, the deadline recomputes to the later reveal time.
 *
 * Rule 4: Outside scheduled windows, daily-usage-limit locks do NOT trigger global UI self-lock;
 * their locked status is displayed on their individual target cards.
 */
object SelfLockPolicy {

    /**
     * Self-lock state container.
     *
     * @property isSelfLocked True if Limiter management controls are locked.
     * @property unlockTimeMillis The epoch millis when the latest active window ends.
     * @property activeLockedPackages List of package names whose windows are currently active.
     */
    data class SelfLockStatus(
        val isSelfLocked: Boolean,
        val unlockTimeMillis: Long? = null,
        val activeLockedPackages: List<String> = emptyList()
    )

    /**
     * Calculates the self-lock status for the given targets and current time.
     */
    fun calculateSelfLockStatus(
        targets: Map<String, TargetAppConfig>,
        nowMillis: Long,
        zone: ZoneId,
        protectedPackages: Set<String> = emptySet()
    ): SelfLockStatus {
        val activeEnds = mutableListOf<Long>()
        val activePackages = mutableListOf<String>()

        for ((pkg, config) in targets) {
            // Protected packages are excluded from restrictions
            if (pkg in protectedPackages) continue

            if (isInsideWindow(config, nowMillis, zone)) {
                val revealMillis = TimeMath.nextOccurrence(nowMillis, zone, config.revealAtMinuteOfDay)
                activeEnds.add(revealMillis)
                activePackages.add(pkg)
            }
        }

        return if (activeEnds.isNotEmpty()) {
            SelfLockStatus(
                isSelfLocked = true,
                unlockTimeMillis = activeEnds.maxOrNull(),
                activeLockedPackages = activePackages
            )
        } else {
            SelfLockStatus(
                isSelfLocked = false,
                unlockTimeMillis = null,
                activeLockedPackages = emptyList()
            )
        }
    }

    /**
     * Check if the given time is within the target's configured lock window.
     *
     * - hideAt < revealAt: [hideAt, revealAt) (same day)
     * - hideAt > revealAt: [hideAt, 1440) OR [0, revealAt) (overnight / crosses midnight)
     */
    fun isInsideWindow(
        config: TargetAppConfig,
        nowMillis: Long,
        zone: ZoneId
    ): Boolean {
        val nowZoned = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val currentMinute = nowZoned.hour * 60 + nowZoned.minute
        // 720 (12:00 PM) paired with 60 (01:00 AM) for Clash Royale is a midnight 12:00 AM input artifact (00:00 = 0)
        val hide = if (config.packageName == com.limiter.app.data.UsageCalculator.CLASH_ROYALE_PACKAGE &&
            config.hideAtMinuteOfDay == 720 && config.revealAtMinuteOfDay == 60) {
            0
        } else {
            config.hideAtMinuteOfDay
        }
        val reveal = config.revealAtMinuteOfDay

        return if (hide < reveal) {
            currentMinute in hide until reveal
        } else {
            currentMinute >= hide || currentMinute < reveal
        }
    }

    /**
     * Format remaining milliseconds into HH:mm:ss.
     */
    fun formatRemainingTime(remainingMillis: Long): String {
        if (remainingMillis <= 0L) return "00:00:00"
        val totalSeconds = remainingMillis / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }
}

/**
 * Action representations for mutations gated by self-lock per Rule B.
 */
sealed interface SelfLockAction {
    data class AddTarget(val packageName: String, val config: TargetAppConfig) : SelfLockAction
    data class RemoveTarget(val packageName: String) : SelfLockAction
    data class EditTarget(
        val packageName: String,
        val oldConfig: TargetAppConfig,
        val newConfig: TargetAppConfig
    ) : SelfLockAction
    data class CancelPending(val packageName: String, val isLoosening: Boolean = true) : SelfLockAction
}

/**
 * Pure gate function evaluating allowed actions during active self-lock per Rule B.
 *
 * Rules:
 * While self-lock is active:
 * - ALLOWED: viewing dashboard, app picker, usage, pending changes;
 *   adding NEW target apps (not already targeted);
 *   decreasing a limit;
 *   cancelling a pending loosening change.
 * - BLOCKED: removing a target, increasing a limit, widening/shortening a window
 *   in the loosening direction, anything classified as loosening by SettingsPolicy.
 */
object SelfLockGate {
    /**
     * Determines whether a typed mutation action is permitted under self-lock.
     */
    fun isActionAllowed(action: SelfLockAction, status: SelfLockPolicy.SelfLockStatus): Boolean {
        if (!status.isSelfLocked) return true
        return when (action) {
            is SelfLockAction.AddTarget -> true
            is SelfLockAction.RemoveTarget -> false
            is SelfLockAction.EditTarget -> {
                val limitLoosened = action.newConfig.dailyLimitMinutes > action.oldConfig.dailyLimitMinutes
                val windowLoosened = SettingsPolicy.isWindowLoosening(
                    action.oldConfig.hideAtMinuteOfDay,
                    action.oldConfig.revealAtMinuteOfDay,
                    action.newConfig.hideAtMinuteOfDay,
                    action.newConfig.revealAtMinuteOfDay
                )
                !limitLoosened && !windowLoosened
            }
            is SelfLockAction.CancelPending -> action.isLoosening // Cancelling loosening is allowed
        }
    }

    fun canAddTarget(status: SelfLockPolicy.SelfLockStatus): Boolean =
        isActionAllowed(SelfLockAction.AddTarget("", TargetAppConfig("", 60, 1380, 420)), status)

    fun canRemoveTarget(status: SelfLockPolicy.SelfLockStatus): Boolean =
        isActionAllowed(SelfLockAction.RemoveTarget(""), status)

    fun canEditTarget(
        status: SelfLockPolicy.SelfLockStatus,
        oldConfig: TargetAppConfig,
        newConfig: TargetAppConfig
    ): Boolean = isActionAllowed(SelfLockAction.EditTarget(oldConfig.packageName, oldConfig, newConfig), status)

    /**
     * Primitive boolean overload.
     */
    fun isActionAllowed(
        isSelfLocked: Boolean,
        isNewTarget: Boolean,
        isLoosening: Boolean
    ): Boolean {
        if (!isSelfLocked) return true
        if (isNewTarget) return true // Adding NEW target allowed during self-lock
        return !isLoosening          // Loosening BLOCKED, tightening ALLOWED
    }

    fun canAddTarget(isSelfLocked: Boolean): Boolean = true

    fun canRemoveTarget(isSelfLocked: Boolean): Boolean = !isSelfLocked

    fun canEditTarget(isSelfLocked: Boolean, isLoosening: Boolean): Boolean =
        !isSelfLocked || !isLoosening
}
