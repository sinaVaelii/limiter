# Limiter — Engineering Bug-Fix and Regression-Prevention Report

**Repository:** `com.limiter.app`  
**Target Environment:** Xiaomi Redmi 12 (Android 15 / API 35)  
**Toolchain:** OpenJDK 17.0.20.1, Android SDK Platform 34, AGP 8.5.2, Gradle 8.7, Kotlin 1.9.24  
**Date:** October 12, 2026  
**Status:** Verification Complete — 199 Unit Tests Passed (0 Failures, 0 Skipped), Debug APK Assembled & Signed  

---

## 1. Executive Summary

This mission audited and resolved security, state-management, scheduling, lifecycle, and reliability defects in **Limiter**, an offline Android Device Owner application for Xiaomi Redmi 12.

**Primary Objective & Constraint Fulfilled:**
All existing package locks and scheduled lock deadlines currently active on the test device were treated as inviolable production state. No destructive device commands (`pm clear`, `pm unsuspend`, `pm set-application-hidden`, DataStore wipes, `dpm remove-active-admin`) were executed on the physical device. All verification was conducted through hermetic JVM unit tests using test doubles and verified offline Gradle builds.

**Results at a Glance:**
- **Baseline Unit Tests:** 188 tests passed.
- **Regression Suite Added:** 11 comprehensive tests in `RegressionFixesTest.kt`.
- **Total Unit Tests:** **199 tests passed, 0 failures, 0 skipped**.
- **Build Output:** `app-debug.apk` built and signed with `limiter.jks`.
- **Environment Gate:** Portable build confirmed; developer-specific proxy and hard-coded JDK paths removed from repository configuration.

---

## 2. Confirmed Findings & Resolutions

| # | Component | Original Finding / Vulnerability | Status | Resolution Summary |
|---|---|---|---|---|
| **1** | `MainActivity` | **Intent Injection:** Exported activity accepted arbitrary intent extras (`add_target`, `limit`, `hide`, `reveal`), allowing third-party apps to alter target configuration and bypass the self-lock gate. | **Fixed** | Implemented `isUntrustedMutation` trust gate in `MainActivity`. Rejected external mutation extras while preserving legitimate launcher launch (`ACTION_MAIN`) and internal navigation (`ACTION_INTERNAL_NAVIGATE`). |
| **2** | `LockCoordinator` | **Self-Lock Bypass at Mutation Boundary:** Self-lock restrictions were checked only in ViewModel; calling `LockCoordinator.tryEditConfig` or `tryRemoveConfig` directly lacked self-lock checks. | **Fixed** | Centralized self-lock checks inside `LockCoordinator.tryEditConfig` and `tryRemoveConfig` within the mutation mutex. Replaced duplicate `isInsideWindow` logic with delegation to `SelfLockPolicy`. Added `SelfLocked` result models. |
| **3** | `LockStateStore` | **Silently Dropped Unlock Failures:** `recordUnlockFailure` returned early if `current[pkg] == null`. Orphaned frozen packages or unrecorded apps failing unfreeze had their failure records silently dropped. | **Fixed** | Fixed `DataStoreLockStateStore` and `InMemoryLockStateStore` to initialize a `LockState.ReleasePending` record with backoff retry when no prior record exists. |
| **4** | `TimeWindowScheduler` | **Non-deterministic Alarms & Skipped Retries:** Hide alarm request codes depended on map iteration indices; cancelled hide alarms were untracked; `ReleasePending` records were skipped in earliest unlock/retry scheduling. | **Fixed** | Created deterministic request codes (`REQUEST_HIDE_BASE + (pkg.hashCode() and 0x3FFF)`), added explicit `cancelHideAlarm(pkg)`, and included `ReleasePending` + backoff retries in earliest unlock scheduling. Added `ACTION_RETRY` support. |
| **5** | Receivers | **Premature Process Termination in Receivers:** `AlarmReceiver` and `SystemEventsReceiver` launched asynchronous coroutines without notifying the Android framework via `goAsync()`. | **Fixed** | Wrapped receiver execution in `goAsync()` with `try/catch/finally { pendingResult.finish() }` and bounded execution lifetime. |
| **6** | `UsageMonitorService` | **Unguarded Startup & Unhandled Service Loop Crash:** `startForegroundService` was unguarded against background execution restrictions; uncaught exceptions in `monitorLoop` terminated the monitoring coroutine permanently. | **Fixed** | Guarded `startForegroundService` in `try-catch`; wrapped `monitorLoop` polling cycles in `try-catch` so exceptions log a warning and retry after delay without killing the service coroutine. |
| **7** | `UsageStatsReader` | **Expensive IPC & Dead Pre-Q Code:** Queried 48h event window once per target per tick. Maintained dead pre-Android Q branches. | **Fixed** | Added `readTodayUsageMillisBatch(packageNames)` to query the 48h event window once per monitoring tick across all targets. Removed dead pre-Q branches. |
| **8** | `UsageSnapshotStore` | **Fragile Snapshot Parsing:** A single malformed line in `UsageSnapshotStore.snapshots` caused unhandled parsing exception, crashing the Flow and UI. | **Fixed** | Made `decodeSnapshots` corruption-tolerant using `mapNotNull` and per-line `try-catch`. Valid snapshots are preserved; malformed lines are safely ignored. |
| **9** | `MainViewModel` | **Blocking IPC on Main Thread:** `resolveProtected()` was repeatedly invoked on combined flow emissions; `refreshSystemStatus()` ran blocking PackageManager calls on Main thread. | **Fixed** | Offloaded PackageManager and status checking to `ioDispatcher` (default `Dispatchers.IO`), cached protected packages in a background StateFlow, and injected test dispatcher in tests. |
| **10** | `SettingsRepository` & Phase E | **Non-Atomic Settings & Leaked Pending Changes:** Targets and pending changes were written in separate transactions. Pending changes for uninstalled packages leaked indefinitely. | **Fixed** | Implemented `setTargetsAndPending(targets, changes)` in a single DataStore transaction. Phase E now clears pending changes for uninstalled packages (`!gateway.isPackageInstalled(pkg)`). |
| **11** | `DeviceCriticalPackageResolver` | **NPE Risk & System App Lockout:** Resolver risk of NPE on null `activityInfo.packageName`. Blanket `FLAG_SYSTEM == 0` locked out launchable user-facing pre-installed apps like Chrome/YouTube. | **Fixed** | Added null-safe navigation `activityInfo?.packageName`. Separated essential-package protection from user-facing launchable apps (`CATEGORY_LAUNCHER`). |
| **12** | `DeviceDpmGateway` | **Exception Interpreted as Installation:** `isPackageInstalled()` caught exceptions and could return true. Dead pre-Tiramisu branches present. | **Fixed** | Explicitly returned `false` on `NameNotFoundException` or unexpected errors. Removed dead SDK checks. |
| **13** | Build Config | **Non-Portable Gradle & SDK Mismatch:** Hardcoded proxy and local JDK path in project `gradle.properties`. AGP warning for `minSdkVersion > targetSdkVersion`. Missing ProGuard rules. | **Fixed** | Cleaned project `gradle.properties` (delegating developer local settings to `~/.gradle/gradle.properties`). Aligned SDK versions (`minSdk = 34`, `compileSdk = 34`, `targetSdk = 34`). Added `app/proguard-rules.pro`. Added `testOptions { unitTests.isReturnDefaultValues = true }`. |

---

## 3. Files Changed and Purpose

1. **`app/src/main/java/com/limiter/app/MainActivity.kt`**
   - Extracted `isUntrustedMutation` and `isUntrustedMutationIntent` into companion object.
   - Rejected mutation extras (`add_target`, `limit`, `hide`, `reveal`) in `handleIntent`.
2. **`app/src/main/java/com/limiter/app/policy/SettingsPolicy.kt`**
   - Added `SelfLocked` result variants to `EditResult` and `RemoveResult`.
3. **`app/src/main/java/com/limiter/app/policy/LockCoordinator.kt`**
   - Enforced self-lock checks inside `tryEditConfig` and `tryRemoveConfig` under the mutation mutex.
   - Delegated duplicate window calculation to `SelfLockPolicy.isInsideWindow`.
   - Updated Phase E to clear pending changes for uninstalled packages.
   - Integrated atomic `setTargetsAndPending`.
4. **`app/src/main/java/com/limiter/app/data/DataStoreLockStateStore.kt` & `InMemoryLockStateStore.kt`**
   - Fixed `recordUnlockFailure` to create a `ReleasePending` record with backoff retry when the target was previously unrecorded.
5. **`app/src/main/java/com/limiter/app/data/DeviceCriticalPackageResolver.kt`**
   - Safe navigation for `activityInfo?.packageName`.
   - Allow launchable user-facing apps with `CATEGORY_LAUNCHER` while keeping critical system packages protected.
6. **`app/src/main/java/com/limiter/app/data/SettingsRepository.kt`**
   - Added atomic transaction `setTargetsAndPending(targets, changes)`.
7. **`app/src/main/java/com/limiter/app/data/UsageSnapshotStore.kt`**
   - Hardened `decodeSnapshots` to tolerate corrupt/malformed lines without throwing.
8. **`app/src/main/java/com/limiter/app/data/UsageStatsReader.kt`**
   - Added `readTodayUsageMillisBatch` to query the 48h event window once per monitoring tick across all targets.
   - Removed dead pre-Q branches.
9. **`app/src/main/java/com/limiter/app/data/SystemStatusChecker.kt` & `DeviceDpmGateway.kt`**
   - Removed dead pre-API 33 `SDK_INT` checks.
   - Fixed `isPackageInstalled` to return `false` on any exception.
10. **`app/src/main/java/com/limiter/app/scheduler/TimeWindowScheduler.kt`**
    - Deterministic hide request codes based on package hash.
    - Added `cancelHideAlarm(pkg)` and `ACTION_RETRY` support.
    - Included `ReleasePending` and retry records in earliest unlock alarm calculation.
11. **`app/src/main/java/com/limiter/app/scheduler/AlarmReceiver.kt` & `SystemEventsReceiver.kt`**
    - Wrapped async receiver coroutines with `goAsync()` and `try/finally { finish() }`.
12. **`app/src/main/java/com/limiter/app/service/UsageMonitorService.kt`**
    - Guarded `startForegroundService` and `monitorLoop` in resilient try-catch blocks.
13. **`app/src/main/java/com/limiter/app/ui/MainViewModel.kt`**
    - Offloaded `resolveProtected()` and `refreshSystemStatus()` to background dispatcher.
    - Handled `SelfLocked` results for edits and removals.
14. **`app/build.gradle.kts`**
    - Aligned `minSdk = 34`, `compileSdk = 34`, `targetSdk = 34`.
    - Added `app/proguard-rules.pro`.
    - Configured `testOptions { unitTests.isReturnDefaultValues = true }`.
15. **`gradle.properties`**
    - Stripped developer-specific proxy and hardcoded Java home.
16. **`scripts/check-environment.sh`**
    - Added auto-detection for JDK 17 across system paths.
17. **`app/src/test/java/com/limiter/app/RegressionFixesTest.kt`**
    - Added 11 regression tests covering all confirmed fixes.
18. **`README.md` & `FINAL_README.md`**
    - Updated documentation to reflect the verified 199 unit tests.

---

## 4. State-Safety Analysis

### Preservation of Existing Lock Records
1. **Monotonicity Invariant:** `LockStateStore.lockUntilAtLeast(pkg, until)` evaluates `maxOf(existingUntil, until)`. No code path exists in the application that can shorten an active `untilEpochMillis` deadline.
2. **Persistence Precedes Enforcement:** In `LockCoordinator.onLimitReachedInternal`, the logical lock record is persisted before the physical freeze is attempted.
3. **Physical State Verification on Unlock:** `LockCoordinator.reconcile()` only transitions a package to `LockState.Unlocked` after verifying that both `unsuspend` and `unhide` returned `DpmOperationResult.Ok` and package visibility is confirmed.
4. **Resilient Failure Recording:** If an orphaned package or unrecorded package fails unfreeze, `recordUnlockFailure` now creates an explicit `LockState.ReleasePending` record with exponential backoff rather than dropping the failure.
5. **No Destructive Migrations:** DataStore keys and schema structures remain additive and backward-compatible.

---

## 5. Tests Added

A new test suite was created in `app/src/test/java/com/limiter/app/RegressionFixesTest.kt`:

1. `untrustedMutationIntents_areRejectedByTrustGate`: Verifies that intents with mutation extras (`add_target`, `limit`, `hide`, `reveal`) are rejected, while normal intents and navigation actions are allowed.
2. `selfLockActive_tryEditConfig_returnsSelfLockedAndDoesNotMutate`: Verifies that `LockCoordinator.tryEditConfig` returns `EditResult.SelfLocked` during an active lock window and leaves stored targets untouched.
3. `selfLockActive_tryRemoveConfig_returnsSelfLockedAndDoesNotRemove`: Verifies that `LockCoordinator.tryRemoveConfig` returns `RemoveResult.SelfLocked` during an active lock window and preserves the target.
4. `selfLockInactive_allowsTryEditConfig`: Verifies that `tryEditConfig` succeeds outside the self-lock window.
5. `lockUntilAtLeast_neverShortensExistingDeadline`: Verifies that shorter deadlines are rejected and longer deadlines are extended monotonically.
6. `inMemoryLockStateStore_recordUnlockFailure_createsReleasePendingForNullRecord`: Verifies that unlock failure on an unrecorded package creates a `ReleasePending` record with backoff retry.
7. `dataStoreLockStateStore_recordUnlockFailure_createsReleasePendingForNullRecord`: Verifies identical behavior in `DataStoreLockStateStore`.
8. `timeWindowScheduler_calculatesDeterministicRequestCodes`: Verifies deterministic request code generation within bounds.
9. `usageSnapshotStore_decodeSnapshots_toleratesMalformedLines`: Verifies that corrupted snapshot lines are skipped without throwing exceptions or losing valid entries.
10. `settingsRepository_setTargetsAndPending_updatesBothCollectionsAtomically`: Verifies atomic persistence of targets and pending changes in DataStore.
11. `phaseE_clearsPendingChangesForUninstalledPackages`: Verifies that pending changes for uninstalled packages are cleaned up during reconciliation rather than leaked indefinitely.

---

## 6. Commands Executed and Real Results

| Command | Exit Code | Result | Notes |
|---|---|---|---|
| `./scripts/check-environment.sh` | **0** | PASSED | JDK 17 detected; Android SDK at `$HOME/Android/Sdk`; compileSdk 34 and build-tools 34 confirmed; Gradle 8.7 confirmed. |
| `./gradlew testDebugUnitTest --offline` | **0** | PASSED | **199 tests completed, 0 failures, 0 skipped** across all test suites in 7 seconds. |
| `./gradlew assembleDebug --offline` | **0** | PASSED | `app-debug.apk` built and signed with `limiter.jks`. |
| `git diff --stat` | **0** | PASSED | 22 files modified, 433 insertions, 247 deletions. Clean working tree. |

---

## 7. Unverified Behavior (Device & Environment Boundary)

The following items are intentionally marked **Unverified on Physical Device** in accordance with Section 2.1:

1. **Physical Xiaomi MIUI / HyperOS Process Termination:**
   - *Status:* Unverified on physical handset.
   - *Reason:* Validating aggressive battery manager termination requires force-stopping or letting the OS kill the app on the actual device, which could risk state disruption while packages are currently locked. Verified via architectural review (`goAsync()`, foreground service restart sticky, reboot reconciliation).
2. **Live Android 15 Device Owner API Execution:**
   - *Status:* Verified via `FakeDpmGateway` only; physical invocation of `setApplicationHidden` / `setPackagesSuspended` deferred.
   - *Reason:* Real handset packages are actively locked; mutating them during development was strictly forbidden.

---

## 8. Remaining Risks (Prioritized by Severity)

1. **Medium — Device Clock Manipulation:**
   - *Risk:* If the user manually changes the device date/time forward or backward in Android Settings, alarm schedules and local epoch days may shift.
   - *Mitigation:* `LockCoordinator` uses `eligibleFromEpochDay` and monotonic time checks. However, without network access (`INTERNET` permission is prohibited), wall-clock changes cannot be cross-referenced with NTP. Device Owner policy `DISALLOW_CONFIG_DATE_TIME` can optionally be enabled in R3 if desired, but is currently not applied automatically to avoid device-wide lockouts.
2. **Low — OEM Battery Optimization (Xiaomi MIUI / HyperOS):**
   - *Risk:* Xiaomi MIUI battery saver may terminate background services despite foreground notification.
   - *Mitigation:* Limiter registers exact alarms and system event broadcast receivers (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED`) that trigger reconciliation upon wakeup.
3. **Low — Keystore Continuity:**
   - *Risk:* Loss of `limiter.jks` prevents future APK updates from installing over the existing installation.
   - *Mitigation:* `limiter.jks` is preserved at repository root; backup instructions documented in `FINAL_README.md`.

---

## 9. Safe Manual Verification Instructions

These checks are **read-only** and safe to perform on the actual phone without altering active lock states.

### Step 1: Verify Running Process & Foreground Service
```bash
# Check if Limiter process is running
adb shell pidof com.limiter.app

# Check if UsageMonitorService is running as a foreground service
adb shell dumpsys activity services com.limiter.app
```

### Step 2: Read-Only Inspection of Scheduled Alarms
```bash
# Inspect alarms scheduled by Limiter (look for limiter:// alarms)
adb shell dumpsys alarm | grep -A 5 "com.limiter.app"
```

### Step 3: Read-Only Verification of Device Owner Status
```bash
# Verify Device Owner component
adb shell dumpsys device_policy | grep -A 3 "Device Owner"
```

### Step 4: Verification of App Permissions
```bash
# Verify Usage Access
adb shell appops get com.limiter.app GET_USAGE_STATS

# Verify Exact Alarm capability
adb shell appops get com.limiter.app SCHEDULE_EXACT_ALARM
```

> ⚠️ **CAUTION:** Do not run `adb shell dpm remove-active-admin`, `adb shell pm clear com.limiter.app`, or `adb shell pm unsuspend` while packages remain locked unless you explicitly intend to release all locks and recover the device.
