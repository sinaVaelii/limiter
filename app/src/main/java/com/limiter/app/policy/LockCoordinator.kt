package com.limiter.app.policy

import com.limiter.app.data.CriticalPackageResolver
import com.limiter.app.data.LockStateStore
import com.limiter.app.data.SettingsRepository
import com.limiter.app.dpm.AppFreezerApi
import com.limiter.app.dpm.DpmGateway
import com.limiter.app.dpm.DpmOperationResult
import com.limiter.app.dpm.PackageInstallStatus
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

        val rawTargets = settingsRepository.snapshotTargets()
        val targets = rawTargets.toMutableMap()
        for ((pkg, config) in rawTargets) {
            if (config.dailyLimitMinutes > SettingsPolicy.MAX_DAILY_LIMIT_MINUTES) {
                val clamped = config.copy(dailyLimitMinutes = SettingsPolicy.MAX_DAILY_LIMIT_MINUTES)
                safeLogE("Clamping target $pkg daily limit from ${config.dailyLimitMinutes} to ${SettingsPolicy.MAX_DAILY_LIMIT_MINUTES} minutes")
                settingsRepository.putTarget(clamped)
                targets[pkg] = clamped
            }
        }
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
            val installStatus = gateway.getPackageInstallStatus(pkg)
            if (installStatus !is PackageInstallStatus.NotInstalled) {
                // If package is Installed OR Unknown (transient error), do NOT treat as missing!
                continue
            }
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

            // Check exponential backoff retry window
            val retryAt = record.nextUnlockRetryAtMillis
            if (retryAt != null && now < retryAt) {
                continue // Backoff has not elapsed yet
            }

            // Query package installation status
            val installStatus = gateway.getPackageInstallStatus(pkg)

            // If package is confirmed missing, Phase C handles physical verification
            if (installStatus is PackageInstallStatus.NotInstalled) continue

            // If status is Unknown:
            // An inconclusive package query does not normally authorize unhide, unsuspend, or release operations.
            // However, after 3 consecutive Unknown failures, attempt unfreeze fallback per release safety gate.
            // If both restoreVisibility and restoreSuspension succeed, transition to Unlocked and commit.
            if (installStatus is PackageInstallStatus.Unknown) {
                var visRestored = record.visibilityRestored || ((state as? LockState.ReleasePending)?.visibilityRestored ?: false)
                var unsusRestored = record.unsuspensionRestored || ((state as? LockState.ReleasePending)?.unsuspensionRestored ?: false)
                if (record.unlockFailureCount >= 3) {
                    if (!unsusRestored) {
                        val unsuspendResult = freezer.restoreSuspension(pkg)
                        if (unsuspendResult is DpmOperationResult.Ok) {
                            unsusRestored = true
                        }
                    }
                    if (!visRestored) {
                        val unhideResult = freezer.restoreVisibility(pkg)
                        if (unhideResult is DpmOperationResult.Ok) {
                            visRestored = true
                        }
                    }
                    if (visRestored && unsusRestored) {
                        lockStateStore.recordUnlock(pkg)
                        lockStateStore.clearUnlockRetry(pkg)
                        lockRecords[pkg] = LockRecord(LockState.Unlocked)
                        events.add(LockEvent.Unlocked(pkg))
                        continue
                    }
                }
                lockStateStore.recordUnlockFailure(
                    pkg = pkg,
                    now = now,
                    visibilityRestored = visRestored,
                    unsuspensionRestored = unsusRestored
                )
                val updated = lockStateStore.snapshot()[pkg]
                if (updated != null) {
                    lockRecords[pkg] = updated
                }
                continue
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
                val retryAt = record.nextUnlockRetryAtMillis
                if (retryAt != null && now < retryAt) {
                    continue
                }
                val frozen = freezer.isFrozen(pkg)
                if (frozen != true) {
                    // Repair physical state — do not change logical until
                    val freezeResult = freezer.freeze(pkg)
                    if (freezeResult !is DpmOperationResult.Ok) {
                        safeLogE("Failed to repair freeze for $pkg: $freezeResult")
                        lockStateStore.recordUnlockFailure(pkg, now)
                        val updated = lockStateStore.snapshot()[pkg]
                        if (updated != null) {
                            lockRecords[pkg] = updated
                        }
                    } else {
                        if (record.unlockFailureCount > 0 || record.nextUnlockRetryAtMillis != null) {
                            lockStateStore.clearUnlockRetry(pkg)
                            val updated = lockStateStore.snapshot()[pkg]
                            if (updated != null) {
                                lockRecords[pkg] = updated
                            }
                        }
                    }
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
                when (gateway.getPackageInstallStatus(change.packageName)) {
                    is PackageInstallStatus.Unknown -> {
                        // Transient error: do NOT discard pending change, do NOT modify targets.
                        // Leave change in remainingPending so it retries on next reconciliation.
                        continue
                    }
                    is PackageInstallStatus.NotInstalled -> {
                        // Package is confirmed not installed on the device.
                        // Ensure removed from targets and clear pending change so it doesn't re-trigger indefinitely.
                        updatedTargets.remove(change.packageName)
                        remainingPending.removeAll { it.packageName == change.packageName }
                        continue
                    }
                    is PackageInstallStatus.Installed -> {
                        if (change.desired != null) {
                            updatedTargets[change.packageName] = change.desired
                        } else {
                            updatedTargets.remove(change.packageName)
                        }
                        remainingPending.removeAll { it.packageName == change.packageName }
                    }
                }
            }

            settingsRepository.setTargetsAndPending(updatedTargets, remainingPending)
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
                val freezeResult = freezer.freeze(pkg)
                if (freezeResult is DpmOperationResult.Ok) {
                    lockRecords[pkg] = LockRecord(LockState.LockedUntil(revealUntil))
                    events.add(LockEvent.WindowLocked(pkg, revealUntil))
                } else {
                    safeLogE("Failed to freeze $pkg during scheduled window: $freezeResult")
                    lockStateStore.recordUnlockFailure(pkg, now)
                    val updated = lockStateStore.snapshot()[pkg]
                    if (updated != null) {
                        lockRecords[pkg] = updated
                    }
                }
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
    @Suppress("UNUSED_PARAMETER")
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
        val freezeResult = freezer.freeze(packageName)
        if (freezeResult !is DpmOperationResult.Ok) {
            safeLogE("Failed to freeze $packageName on limit reached: $freezeResult")
            lockStateStore.recordUnlockFailure(packageName, now)
        }
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
    ): Boolean = SelfLockPolicy.isInsideWindow(config, nowMillis, zone)

    /**
     * Submit a configuration edit per §38.
     * Enforces self-lock policy at the authoritative mutation boundary within the single mutex.
     */
    suspend fun tryEditConfig(config: TargetAppConfig): com.limiter.app.policy.SettingsPolicy.EditResult = mutex.withLock {
        val now = timeSource.nowMillis()
        val zone = timeSource.zone()
        
        val targets = settingsRepository.snapshotTargets()
        val protectedPkgs = criticalPackageResolver.resolveProtected()

        // Enforce self-lock: management controls locked during active restriction window
        val selfLock = SelfLockPolicy.calculateSelfLockStatus(targets, now, zone, protectedPkgs)
        val isNewTarget = config.packageName !in targets

        // Protected packages cannot be added or edited as targets
        if (config.packageName in protectedPkgs) {
            return@withLock com.limiter.app.policy.SettingsPolicy.EditResult.Protected
        }
        
        // Treat as Add if it doesn't exist
        if (isNewTarget) {
            if (!SelfLockGate.isActionAllowed(SelfLockAction.AddTarget(config.packageName, config), selfLock)) {
                return@withLock com.limiter.app.policy.SettingsPolicy.EditResult.SelfLocked
            }
            val addResult = com.limiter.app.policy.SettingsPolicy.evaluateAdd(config, targets, protectedPkgs)
            if (addResult is com.limiter.app.policy.SettingsPolicy.AddResult.Ok) {
                settingsRepository.putTarget(addResult.config)
                return@withLock com.limiter.app.policy.SettingsPolicy.EditResult.Applied(addResult.config)
            } else if (addResult is com.limiter.app.policy.SettingsPolicy.AddResult.Protected) {
                return@withLock com.limiter.app.policy.SettingsPolicy.EditResult.Protected
            } else if (addResult is com.limiter.app.policy.SettingsPolicy.AddResult.Duplicate) {
                return@withLock com.limiter.app.policy.SettingsPolicy.EditResult.Duplicate
            } else if (addResult is com.limiter.app.policy.SettingsPolicy.AddResult.SelfLocked) {
                return@withLock com.limiter.app.policy.SettingsPolicy.EditResult.SelfLocked
            } else if (addResult is com.limiter.app.policy.SettingsPolicy.AddResult.Invalid) {
                return@withLock com.limiter.app.policy.SettingsPolicy.EditResult.Invalid(addResult.reason)
            } else {
                return@withLock com.limiter.app.policy.SettingsPolicy.EditResult.Invalid("Add failed: $addResult")
            }
        }

        val existing = targets[config.packageName] ?: return@withLock com.limiter.app.policy.SettingsPolicy.EditResult.NotFound

        // Enforce self-lock: blocks loosening (limit increase, window loosening), allows tightening (e.g. limit decrease)
        val editAction = SelfLockAction.EditTarget(config.packageName, existing, config)
        if (!SelfLockGate.isActionAllowed(editAction, selfLock)) {
            return@withLock com.limiter.app.policy.SettingsPolicy.EditResult.SelfLocked
        }

        val editResult = com.limiter.app.policy.SettingsPolicy.evaluateEdit(config, targets, now, zone)

        when (editResult) {
            is com.limiter.app.policy.SettingsPolicy.EditResult.Applied -> {
                settingsRepository.putTarget(editResult.config)
                settingsRepository.removePending(config.packageName)
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
     * Enforces self-lock policy at the authoritative mutation boundary within the single mutex.
     */
    suspend fun tryRemoveConfig(packageName: String): com.limiter.app.policy.SettingsPolicy.RemoveResult = mutex.withLock {
        val now = timeSource.nowMillis()
        val zone = timeSource.zone()
        val targets = settingsRepository.snapshotTargets()
        val protectedPkgs = criticalPackageResolver.resolveProtected()

        // Enforce self-lock: removing a target is loosening and strictly blocked during self-lock
        val selfLock = SelfLockPolicy.calculateSelfLockStatus(targets, now, zone, protectedPkgs)
        if (!SelfLockGate.isActionAllowed(SelfLockAction.RemoveTarget(packageName), selfLock)) {
            return@withLock com.limiter.app.policy.SettingsPolicy.RemoveResult.SelfLocked
        }
        
        val removeResult = com.limiter.app.policy.SettingsPolicy.evaluateRemove(packageName, targets, now, zone)
        if (removeResult is com.limiter.app.policy.SettingsPolicy.RemoveResult.Deferred) {
            settingsRepository.putPending(removeResult.pending)
        }
        
        return@withLock removeResult
    }

    companion object {
        private fun safeLogE(msg: String, tr: Throwable? = null) {
            try {
                if (tr != null) android.util.Log.e("Limiter", msg, tr) else android.util.Log.e("Limiter", msg)
            } catch (_: Throwable) {
                System.err.println("Limiter: $msg")
            }
        }
    }
}
