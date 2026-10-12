# Report P3 — Lock/unlock correctness (H3, M1, M2, M3, M8, M9, fallback)
Commit before/after: `75bf8b2` → `815c67c`

## Done (ID → what, file:line)
- H3: `UsageMonitorService` calls `reconcileIfDue` and consolidates `reschedule()` once per tick (`UsageMonitorService.kt:168,179`, `UsageMonitorWatchdog.kt:48-58`).
- M2: `freezer.freeze()` result verified; failure schedules bounded retry with backoff and logs error (`LockCoordinator.kt:318-333,403-414,544-548`).
- M3: `recordUnlockFailure` creates minimal `ReleasePending` record when record is null (`DataStoreLockStateStore.kt:142-149`, `InMemoryLockStateStore.kt:102-109`).
- M9: `getPackageInstallStatus` / `isPackageInstalled` return `Unknown` / `false` on unexpected exceptions (`DeviceDpmGateway.kt:186-209,286`).
- Fallback: after 3 consecutive `Unknown` query failures, unfreeze fallback is attempted; unlock commits only if both unhide and unsuspend return `Ok` (`LockCoordinator.kt:224-258`).
- M1 & M8: confirmed `NotInstalled` removes targets and clears pending changes in Phase E (`LockCoordinator.kt:360-366`); atomic `setTargetsAndPending` preserved (`SettingsRepository.kt:76-84`).

## Verified (COMPILED | TESTED (JVM) | VERIFIED ON DEVICE | NOT RUN) + new test names + counts (total tests before → after)
- COMPILED: `./gradlew assembleDebug --offline` (exit 0)
- TESTED (JVM): `./gradlew testDebugUnitTest --offline` (exit 0; 243 → 248 tests, all passed)
  - `usageMonitorWatchdog_reconcileIfDue_nothingDue_zeroCalls_and_due_exactlyOneCall`
  - `freezeFailure_duringWindowLock_doesNotRecordLockedVerified_schedulesRetryWithBackoff`
  - `freezeFailure_onLimitReached_schedulesRetryWithBackoff`
  - `evaluateStatus_unexpectedException_returnsUnknown_andIsPackageInstalledReturnsFalse`
  - `fallbackAgainstStuckLocks_whenRestoreFailsAtThreeFailures_preservesRetryStateAndDoesNotUnlock`
- VERIFIED ON DEVICE: NOT RUN (P3 authorizes no phone installations).

## Not done / blocked / needs my decision
- None for P3. Ready for P4.

## Changed files (git diff --stat, one-line reason each)
- `app/src/main/java/com/limiter/app/dpm/DeviceDpmGateway.kt`: M9 unexpected exception handling in `getPackageInstallStatus`.
- `app/src/main/java/com/limiter/app/policy/LockCoordinator.kt`: M2 freeze result checking/retry, stuck locks fallback, Phase E uninstalled cleanup.
- `app/src/main/java/com/limiter/app/service/UsageMonitorService.kt`: H3 reschedule consolidation and `reconcileIfDue` integration.
- `app/src/main/java/com/limiter/app/service/UsageMonitorWatchdog.kt`: H3 `reconcileIfDue` execution helper.
- `app/src/test/java/com/limiter/app/RegressionFixesTest.kt`: unit tests for H3, M2, M9, and fallback against stuck locks.

## Risks / things I should test on the phone
- If an app's freeze fails due to Xiaomi background restriction during sleep, verify retry kicks in on next tick.
- Verify uninstalled apps with pending changes cleanly disappear after reconciliation.
