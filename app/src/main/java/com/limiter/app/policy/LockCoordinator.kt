package com.limiter.app.policy

import com.limiter.app.data.CriticalPackageResolver
import com.limiter.app.data.LockStateStore
import com.limiter.app.data.SettingsRepository
import com.limiter.app.dpm.AppFreezerApi
import com.limiter.app.dpm.DpmGateway
import com.limiter.app.dpm.DpmOperationResult
import com.limiter.app.model.LockRecord
import com.limiter.app.model.LockState
import com.limiter.app.model.PendingChange
import com.limiter.app.model.TargetAppConfig
import com.limiter.app.service.UsageMonitorService
import com.limiter.app.time.TimeMath
import com.limiter.app.time.TimeSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LockCoordinator per AGENTS.md §28-37.
 *
 * This is the authoritative lock transition boundary.
 * Only LockCoordinator owns AppFreezerApi.
 * Only LockCoordinator.reconcile() may transition LockedUntil -> Unlocked.
 * All mutations affecting settings/lock state/physical freeze are serialized by one Mutex.
 *
 * There is no unlock(), forceUnlock(), snooze, or any bypass API.
 */
class LockCoordinator(
    private val gateway: DpmGateway,
    private val freezer: AppFreezerApi,
    private val lockStateStore: LockStateStore,
    private val settingsRepository: SettingsRepository,
    private val criticalPackageResolver: CriticalPackageResolver,
    private val timeSource: TimeSource,
    private val selfPackageName: String = "com.limiter.app"
) : UsageMonitorService.LimitReachedCallback {

    /**
     * Events emitted during reconciliation.
     */
    sealed interface LockEvent {
        data class LimitLocked(val pkg: String, val until: Long) : LockEvent
        data class WindowLocked(val pkg: String, val until: Long) : LockEvent
        data class Unlocked(val pkg: String) : LockEvent
    }

    /**
     * Sealed result for self-protection policy application.
     */
    sealed interface SelfProtectionResult {
        data object Applied : SelfProtectionResult
        data object NotDeviceOwner : SelfProtectionResult
        data class PartialOrFailed(
            val uninstallResult: DpmOperationResult,
            val userControlResult: DpmOperationResult
        ) : SelfProtectionResult
    }

    /** Single mutex for all lock/settings/physical-freeze mutations. */
    private val mutex = Mutex()

    /**
     * Sealed result of reconciliation.
     */
    sealed interface ReconcileResult {
        data class Ok(
            val events: List<LockEvent>,
            val selfProtection: SelfProtectionResult = SelfProtectionResult.Applied
        ) : ReconcileResult
        data object NotDeviceOwner : ReconcileResult
    }

    /**
     * Full reconciliation per AGENTS.md §30-36.
     *
     * Idempotent. Reconciles desired state + persisted state + actual DPM state.
     */
    suspend fun reconcile(): ReconcileResult = mutex.withLock {
        reconcileInternal()
    }

    /**
     * Called by the monitor service when a package's daily limit is reached.
     * Per AGENTS.md §37.
     */
    override suspend fun onLimitReached(packageName: String, usedMillis: Long) {
        mutex.withLock {
            onLimitReachedInternal(packageName, usedMillis)
        }
    }

    /**
     * Called on each monitor tick to trigger reconciliation of expired locks
     * and scheduled windows.
     */
    suspend fun onTick() {
        reconcile()
    }

    // -----------------------------------------------------------------------
    // Internal reconciliation implementation
    // -----------------------------------------------------------------------

    private suspend fun reconcileInternal(): ReconcileResult {
        val now = timeSource.nowMillis()
        val zone = timeSource.zone()
        val events = mutableListOf<LockEvent>()

        // Phase A — Snapshot
        if (!gateway.isDeviceOwner()) {
            return ReconcileResult.NotDeviceOwner
        }

        val targets = settingsRepository.snapshotTargets()
        val pendingChanges = settingsRepository.snapshotPending()
        val lockRecords = lockStateStore.snapshot().toMutableMap()
        val protectedPkgs = criticalPackageResolver.resolveProtected()

        // Phase B — Build Recovery Set (Requirement 1)
        // Only recover packages using reliable Limiter-managed history:
        // configured targets + pending changes + persisted lock records.
        // Discovering a frozen package does not by itself prove Limiter owns it.
        val managedPackages = (targets.keys + pendingChanges.map { it.packageName } + lockRecords.keys).toSet()
        val recoverySet = managedPackages.toMutableSet()

        // Phase C — Recover Missing Packages & Preserve Recovery History (Requirement 2)
        // Never forget or remove a package's persistent record until its required physical restoration
        // has succeeded (hidden == false && suspended == false).
        for (pkg in recoverySet.toSet()) {
            if (!gateway.isPackageInstalled(pkg)) {
                val record = lockRecords[pkg]
                if (record != null) {
                    val lockedUntil = record.state as? LockState.LockedUntil
                    // Preserve active restrictions on configured targets (Requirement 5):
                    // Active lock on configured target (pkg in targets && now < untilEpochMillis)
                    // must NEVER be destroyed by transient package visibility or temporary detection failures.
                    if (pkg in targets && lockedUntil != null && now < lockedUntil.untilEpochMillis) {
                        continue
                    }

                    // For an expired lock, release-pending, or unconfigured missing package,
                    // verify physical restoration before forgetting.
                    val isHidden = freezer.isHidden(pkg) == true
                    val isSuspended = freezer.isSuspended(pkg) == true

                    if (isHidden || isSuspended) {
                        // Package is still physically restricted; attempt independent unfreeze
                        var visRestored = !isHidden
                        var unsuspRestored = !isSuspended

                        if (!unsuspRestored) {
                            if (freezer.restoreSuspension(pkg) is DpmOperationResult.Ok) {
                                unsuspRestored = true
                            }
                        }
                        if (!visRestored) {
                            if (freezer.restoreVisibility(pkg) is DpmOperationResult.Ok) {
                                visRestored = true
                            }
                        }

                        if (!visRestored || !unsuspRestored) {
                            // Unfreeze failed; retain recovery record with backoff
                            lockStateStore.recordUnlockFailure(pkg, now, visRestored, unsuspRestored)
                            val updated = lockStateStore.snapshot()[pkg]
                            if (updated != null) {
                                lockRecords[pkg] = updated
                            }
                            continue // Do NOT forget while physical restoration is incomplete!
                        }
                    }

                    // Physical restoration has succeeded (package is neither hidden nor suspended).
                    // If the package is no longer a target and has no pending changes, it can be safely forgotten.
                    if (pkg !in targets && pendingChanges.none { it.packageName == pkg }) {
                        lockStateStore.recordUnlock(pkg)
                        lockStateStore.clearUnlockRetry(pkg)
                        lockStateStore.forget(pkg)
                        lockRecords.remove(pkg)
                    } else {
                        // Still in targets: transition to Unlocked
                        lockStateStore.recordUnlock(pkg)
                        lockStateStore.clearUnlockRetry(pkg)
                        lockRecords[pkg] = LockRecord(LockState.Unlocked)
                    }
                }
            }
        }

        // Phase D — Resolve Expired Locks and ReleasePending FIRST (Requirement 3)
        for (pkg in recoverySet.toSet()) {
            val record = lockRecords[pkg] ?: continue
            val state = record.state

            val needsRelease = when (state) {
                is LockState.LockedUntil -> now >= state.untilEpochMillis
                is LockState.ReleasePending -> true
                is LockState.Unlocked -> false
            }

            if (!needsRelease) continue

            // If package is missing, Phase C handles physical verification
            if (!gateway.isPackageInstalled(pkg)) continue

            // Check exponential backoff retry window
            val retryAt = record.nextUnlockRetryAtMillis
            if (retryAt != null && now < retryAt) {
                continue // Backoff has not elapsed yet
            }

            // Determine current restoration status
            var visibilityRestored = record.visibilityRestored || ((state as? LockState.ReleasePending)?.visibilityRestored ?: false)
            var unsuspensionRestored = record.unsuspensionRestored || ((state as? LockState.ReleasePending)?.unsuspensionRestored ?: false)

            // Requirement 3: Attempt unsuspend and unhide independently, retry ONLY incomplete operations
            if (!unsuspensionRestored) {
                val unsuspendResult = freezer.restoreSuspension(pkg)
                if (unsuspendResult is DpmOperationResult.Ok) {
                    unsuspensionRestored = true
                }
            }

            if (!visibilityRestored) {
                val unhideResult = freezer.restoreVisibility(pkg)
                if (unhideResult is DpmOperationResult.Ok) {
                    visibilityRestored = true
                }
            }

            if (visibilityRestored && unsuspensionRestored) {
                // Both restored successfully!
                lockStateStore.recordUnlock(pkg)
                lockStateStore.clearUnlockRetry(pkg)
                lockRecords[pkg] = LockRecord(LockState.Unlocked)
                events.add(LockEvent.Unlocked(pkg))
            } else {
                // Partial or total failure: persist ReleasePending with partial progress flags and schedule backoff
                lockStateStore.recordUnlockFailure(
                    pkg = pkg,
                    now = now,
                    visibilityRestored = visibilityRestored,
                    unsuspensionRestored = unsuspensionRestored
                )
                val updated = lockStateStore.snapshot()[pkg]
                if (updated != null) {
                    lockRecords[pkg] = updated
                }
            }
        }

        // Phase D.5 — Repair Active Locks (§31)
        for (pkg in recoverySet.toSet()) {
            if (!gateway.isPackageInstalled(pkg)) continue
            val record = lockRecords[pkg] ?: continue
            val lockedUntil = record.state as? LockState.LockedUntil ?: continue

            // Requirement 3: Ensure reconciliation cannot re-freeze a package while it is
            // undergoing release (state is not ReleasePending) or protected.
            if (now < lockedUntil.untilEpochMillis && pkg !in protectedPkgs) {
                val frozen = freezer.isFrozen(pkg)
                if (frozen != true) {
                    // Repair physical state — do not change logical until
                    freezer.freeze(pkg)
                }
            }
        }

        // Phase E — Apply Pending Changes AFTER Unlock (§32)
        val todayEpochDay = TimeMath.localEpochDay(now, zone)
        // Re-read lock records since some may have been unlocked
        val currentLockStates = lockRecords.mapValues { (_, record) -> record.state }
        val eligiblePending = SettingsPolicy.filterEligiblePending(
            pending = pendingChanges,
            todayEpochDay = todayEpochDay,
            lockStates = currentLockStates,
            protectedPackages = protectedPkgs
        )

        if (eligiblePending.isNotEmpty()) {
            val updatedTargets = targets.toMutableMap()
            val remainingPending = pendingChanges.toMutableList()

            for (change in eligiblePending) {
                if (!gateway.isPackageInstalled(change.packageName)) continue
                if (change.desired != null) {
                    updatedTargets[change.packageName] = change.desired
                } else {
                    updatedTargets.remove(change.packageName)
                }
                remainingPending.removeAll { it.packageName == change.packageName }
            }

            settingsRepository.setTargets(updatedTargets)
            settingsRepository.setPending(remainingPending)
        }

        // Recalculate effective targets after pending changes
        val effectiveTargets = settingsRepository.snapshotTargets()

        // Phase F — New Scheduled Window Locks (§33)
        for ((pkg, config) in effectiveTargets) {
            if (!gateway.isPackageInstalled(pkg)) continue
            val record = lockRecords[pkg]
            // Requirement 3: Never re-lock a package that is already actively locked OR undergoing release!
            if (record != null) {
                val isActivelyLocked = record.state is LockState.LockedUntil && now < record.state.untilEpochMillis
                val isUndergoingRelease = record.state is LockState.ReleasePending ||
                        record.nextUnlockRetryAtMillis != null ||
                        record.unlockFailureCount > 0 ||
                        record.unsuspensionRestored ||
                        record.visibilityRestored
                if (isActivelyLocked || isUndergoingRelease) continue
            }
            if (pkg in protectedPkgs) continue

            if (isInsideWindow(config, now, zone)) {
                val revealUntil = TimeMath.nextOccurrence(now, zone, config.revealAtMinuteOfDay)
                lockStateStore.lockUntilAtLeast(pkg, revealUntil)
                freezer.freeze(pkg)
                lockRecords[pkg] = LockRecord(LockState.LockedUntil(revealUntil))
                events.add(LockEvent.WindowLocked(pkg, revealUntil))
            }
        }

        // Phase G — Orphan physical freeze recovery (§35)
        for (pkg in recoverySet.toSet()) {
            if (!gateway.isPackageInstalled(pkg)) continue
            val record = lockRecords[pkg]
            val isUnlocked = record == null || record.state is LockState.Unlocked

            if (isUnlocked) {
                val hidden = freezer.isHidden(pkg) == true
                val suspended = freezer.isSuspended(pkg) == true

                if (hidden || suspended) {
                    // Check if this package should be newly locked by window/limit rules
                    val config = effectiveTargets[pkg]
                    val shouldBeLocked = config != null &&
                            pkg !in protectedPkgs &&
                            isInsideWindow(config, now, zone)

                    if (!shouldBeLocked) {
                        // Attempt independent unfreeze
                        var visRestored = !hidden
                        var unsuspRestored = !suspended

                        if (!unsuspRestored) {
                            if (freezer.restoreSuspension(pkg) is DpmOperationResult.Ok) {
                                unsuspRestored = true
                            }
                        }
                        if (!visRestored) {
                            if (freezer.restoreVisibility(pkg) is DpmOperationResult.Ok) {
                                visRestored = true
                            }
                        }

                        if (visRestored && unsuspRestored) {
                            lockStateStore.recordUnlock(pkg)
                            lockStateStore.clearUnlockRetry(pkg)
                            lockRecords[pkg] = LockRecord(LockState.Unlocked)
                            events.add(LockEvent.Unlocked(pkg))
                        } else {
                            // Persist ReleasePending and retry on failure so recovery history is NOT lost!
                            lockStateStore.recordUnlockFailure(
                                pkg = pkg,
                                now = now,
                                visibilityRestored = visRestored,
                                unsuspensionRestored = unsuspRestored
                            )
                            val updated = lockStateStore.snapshot()[pkg]
                            if (updated != null) {
                                lockRecords[pkg] = updated
                            }
                        }
                    }
                }
            }
        }

        // Phase H — Protected transition (§36)
        for (pkg in recoverySet.toSet()) {
            if (!gateway.isPackageInstalled(pkg)) continue
            if (pkg !in protectedPkgs) continue

            val record = lockRecords[pkg]
            val isRestricted = freezer.isHidden(pkg) == true || freezer.isSuspended(pkg) == true

            // Protected + no active lock record (null or Unlocked) + physically restricted -> recover
            if ((record == null || record.state is LockState.Unlocked) && isRestricted) {
                freezer.restoreSuspension(pkg)
                freezer.restoreVisibility(pkg)
            }
        }

        // Enforce self-protection policies (§24, §45)
        val selfProtection = enforceSelfProtection()

        return ReconcileResult.Ok(events, selfProtection)
    }

    /**
     * onLimitReached per AGENTS.md §37.
     */
    private suspend fun onLimitReachedInternal(packageName: String, usedMillis: Long) {
        val now = timeSource.nowMillis()
        val zone = timeSource.zone()

        // Ignore if Device Owner absent
        if (!gateway.isDeviceOwner()) return

        // Ignore if package not installed
        if (!gateway.isPackageInstalled(packageName)) return

        // Ignore if protected
        val protectedPkgs = criticalPackageResolver.resolveProtected()
        if (packageName in protectedPkgs) return

        // Ignore if not effective target
        val targets = settingsRepository.snapshotTargets()
        val config = targets[packageName] ?: return

        // Ignore if already locked until a future instant OR undergoing release
        val lockRecords = lockStateStore.snapshot()
        val existing = lockRecords[packageName]
        if (existing != null) {
            val lockedUntil = existing.state as? LockState.LockedUntil
            if (lockedUntil != null && now < lockedUntil.untilEpochMillis) {
                return // Already locked
            }
            val isUndergoingRelease = existing.state is LockState.ReleasePending ||
                    existing.nextUnlockRetryAtMillis != null ||
                    existing.unlockFailureCount > 0 ||
                    existing.unsuspensionRestored ||
                    existing.visibilityRestored
            if (isUndergoingRelease) {
                return // Undergoing release, do not re-lock
            }
        }

        // Calculate lock-until per §34
        val nextReveal = TimeMath.nextOccurrence(now, zone, config.revealAtMinuteOfDay)
        val nextMidnight = TimeMath.nextLocalMidnightMillis(now, zone)
        val until = maxOf(nextReveal, nextMidnight)

        // 1. Persist lock (persist first, then freeze)
        lockStateStore.lockUntilAtLeast(packageName, until)

        // 2. Freeze
        freezer.freeze(packageName)
    }

    /**
     * Enforce Device Owner policies on Limiter itself per §24 and §45.
     */
    fun enforceSelfProtection(): SelfProtectionResult {
        if (!gateway.isDeviceOwner()) return SelfProtectionResult.NotDeviceOwner

        // Block uninstall
        val uninstallResult = gateway.setUninstallBlocked(selfPackageName, true)

        // Disable user control (prevents clear-data, force-stop)
        val userControlResult = gateway.setUserControlDisabled(listOf(selfPackageName))

        return if (uninstallResult is DpmOperationResult.Ok && userControlResult is DpmOperationResult.Ok) {
            SelfProtectionResult.Applied
        } else {
            SelfProtectionResult.PartialOrFailed(
                uninstallResult = uninstallResult,
                userControlResult = userControlResult
            )
        }
    }

    /**
     * Check if the current time is inside the configured lock window.
     * Per §33 window semantics:
     *   hideAt < revealAt: [hideAt, revealAt)
     *   hideAt > revealAt: [hideAt, 1440) OR [0, revealAt)
     */
    private fun isInsideWindow(
        config: TargetAppConfig,
        nowMillis: Long,
        zone: java.time.ZoneId
    ): Boolean {
        val nowZoned = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone)
        val currentMinute = nowZoned.hour * 60 + nowZoned.minute
        val hide = config.hideAtMinuteOfDay
        val reveal = config.revealAtMinuteOfDay

        return if (hide < reveal) {
            // Window does not cross midnight: [hide, reveal)
            currentMinute in hide until reveal
        } else {
            // Window crosses midnight: [hide, 1440) OR [0, reveal)
            currentMinute >= hide || currentMinute < reveal
        }
    }

    /**
     * Submit a configuration edit per §38 (approx).
     * Handled within the single mutex to avoid race conditions.
     */
    suspend fun tryEditConfig(config: TargetAppConfig): com.limiter.app.policy.SettingsPolicy.EditResult = mutex.withLock {
        val now = timeSource.nowMillis()
        val zone = timeSource.zone()
        
        val targets = settingsRepository.snapshotTargets()
        val protectedPkgs = criticalPackageResolver.resolveProtected()

        // Protected packages cannot be added or edited as targets
        if (config.packageName in protectedPkgs) {
            return@withLock com.limiter.app.policy.SettingsPolicy.EditResult.Protected
        }
        
        // Treat as Add if it doesn't exist
        if (config.packageName !in targets) {
            val addResult = com.limiter.app.policy.SettingsPolicy.evaluateAdd(config, targets, protectedPkgs)
            if (addResult is com.limiter.app.policy.SettingsPolicy.AddResult.Ok) {
                settingsRepository.putTarget(addResult.config)
                return@withLock com.limiter.app.policy.SettingsPolicy.EditResult.Applied(addResult.config)
            } else if (addResult is com.limiter.app.policy.SettingsPolicy.AddResult.Protected) {
                return@withLock com.limiter.app.policy.SettingsPolicy.EditResult.Protected
            } else {
                return@withLock com.limiter.app.policy.SettingsPolicy.EditResult.Invalid("Add failed: $addResult")
            }
        }
        
        val editResult = com.limiter.app.policy.SettingsPolicy.evaluateEdit(config, targets, now, zone)
        when (editResult) {
            is com.limiter.app.policy.SettingsPolicy.EditResult.Applied -> {
                settingsRepository.putTarget(editResult.config)
            }
            is com.limiter.app.policy.SettingsPolicy.EditResult.Deferred -> {
                settingsRepository.putPending(editResult.pending)
            }
            is com.limiter.app.policy.SettingsPolicy.EditResult.PartialApplied -> {
                settingsRepository.putTarget(editResult.appliedNow)
                settingsRepository.putPending(editResult.pending)
            }
            else -> {}
        }
        
        return@withLock editResult
    }

    /**
     * Submit a configuration removal.
     */
    suspend fun tryRemoveConfig(packageName: String): com.limiter.app.policy.SettingsPolicy.RemoveResult = mutex.withLock {
        val now = timeSource.nowMillis()
        val zone = timeSource.zone()
        val targets = settingsRepository.snapshotTargets()
        
        val removeResult = com.limiter.app.policy.SettingsPolicy.evaluateRemove(packageName, targets, now, zone)
        if (removeResult is com.limiter.app.policy.SettingsPolicy.RemoveResult.Deferred) {
            settingsRepository.putPending(removeResult.pending)
        }
        
        return@withLock removeResult
    }
}
