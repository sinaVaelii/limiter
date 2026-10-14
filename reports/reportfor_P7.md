# Report P7 — Config Cleanup, Dead Code Removal & Documentation
Commit before/after: e349245 -> 3be29e2

## Done
- B2/D1: Verified `compileSdk=targetSdk=minSdk=34` in `app/build.gradle.kts:22,27,28` and `scripts/check-environment.sh:56`.
- B1: Verified `gradle.properties` has no hard-coded `org.gradle.java.home` or proxy.
- B5: Cleaned up stale Stage 1 comments in `app/src/main/res/xml/device_admin.xml:4-6`.
- B6: Removed redundant `SDK_INT >= S` dynamic color check and unused imports in `Theme.kt:3,24`.
- L1: Removed dead symbols `LockCoordinator.onTick` and `getAllFrozenPackages` across `DpmGateway.kt`, `DeviceDpmGateway.kt`, `FakeDpmGateway.kt` after verifying 0 usages with `grep -rn`.
- L2: Refactored 4x repeated unfreeze blocks in `LockCoordinator.kt:154,228,266,437` into `attemptIndependentUnfreeze`. Made `LimiterApp.lockCoordinator` and `timeWindowScheduler` non-null. Added warning log to empty catch in `MainActivity.kt:62`.
- T1: Added 7 JVM tests in `CleanupAndResilienceP7Test.kt` verifying target removal cancellation, hide window change cancellation, direct package alarm cancellation, `cancelAll()`, watchdog due decisions, and receiver completion.
- D1: Updated `README.md` with 288 tests count, `adb shell dpm remove-active-admin` recovery command, and 720m/+30m limit and self-lock rules.

## Verified
- Status: TESTED (JVM), COMPILED
- Total unit tests: 281 -> 288 (0 failures, 288 passed)
- New tests:
  - `CleanupAndResilienceP7Test.scheduler_targetRemoval_activelyCancelsHideAlarmOnDispatcher`
  - `CleanupAndResilienceP7Test.scheduler_windowChange_cancelsPreviousHideAlarmBeforeRescheduling`
  - `CleanupAndResilienceP7Test.scheduler_cancelHideAlarm_directlyCancelsCorrectRequestCode`
  - `CleanupAndResilienceP7Test.scheduler_cancelAll_cancelsSystemAlarmsAndAllTrackedHideAlarms`
  - `CleanupAndResilienceP7Test.watchdog_reconcileIfDue_executesCallbackOnlyWhenDue`
  - `CleanupAndResilienceP7Test.alarmReceiver_unhandledAction_invokesFinishExactlyOnce`
  - `CleanupAndResilienceP7Test.systemEventsReceiver_unhandledAction_invokesFinishExactlyOnce`

## Not Done / Blocked / Needs Decision
- None. All P7 tasks, cleanup, and documentation completed.

## Changed Files (`git diff --stat HEAD~1`)
- `README.md`: Updated final test count (288), recovery instructions, and 720m/+30m rules.
- `app/src/main/java/com/limiter/app/LimiterApp.kt`: Non-null coordinator and scheduler.
- `app/src/main/java/com/limiter/app/MainActivity.kt`: Replaced empty catch block with logging.
- `app/src/main/java/com/limiter/app/dpm/DeviceDpmGateway.kt`: Removed dead `getAllFrozenPackages`.
- `app/src/main/java/com/limiter/app/dpm/DpmGateway.kt`: Removed dead `getAllFrozenPackages` from interface.
- `app/src/main/java/com/limiter/app/policy/LockCoordinator.kt`: Removed `onTick`; consolidated unfreeze blocks.
- `app/src/main/java/com/limiter/app/scheduler/AlarmReceiver.kt`: Direct access to non-null `lockCoordinator`.
- `app/src/main/java/com/limiter/app/scheduler/SystemEventsReceiver.kt`: Direct access to non-null `lockCoordinator`.
- `app/src/main/java/com/limiter/app/service/UsageMonitorService.kt`: Direct access to coordinator and scheduler.
- `app/src/main/java/com/limiter/app/ui/theme/Theme.kt`: Removed redundant API level check and unused imports.
- `app/src/main/res/xml/device_admin.xml`: Removed stale Stage 1 comments.
- `app/src/test/java/com/limiter/app/dpm/FakeDpmGateway.kt`: Removed dead `getAllFrozenPackages`.
- `app/src/test/java/com/limiter/app/CleanupAndResilienceP7Test.kt`: 7 new unit tests for T1.

## Risks / Phone Checks for User
- Verify `adb shell dpm remove-active-admin com.limiter.app/.admin.LimiterAdminReceiver` only if testing emergency admin de-provisioning.
- Normal test: install debug APK via `adb install -r -t` and verify app targets are displayed correctly.
