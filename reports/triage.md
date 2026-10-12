# Triage Report (Phase P0)

Baseline commit: `fe4c677`

## 1. Issue Catalog Triage Table

| ID | Status | Evidence (file:line) | Planned Phase |
|---|---|---|---|
| **H1** | ALREADY FIXED | `MainActivity.kt:45-50`, `107-112`; mutation extras rejected by `IntentGateDecision.REJECT_MUTATION`, test in `RegressionFixesTest.kt:115` | P1 |
| **H2** | PARTIALLY FIXED / CONFIRMED | `LockCoordinator.kt:568,621` guards mutations with `SelfLockPolicy`; but `LimiterScreen.kt:298` uses `System.currentTimeMillis()` and `_currentMillis` doesn't tick on dashboard | P4 |
| **H3** | ALREADY FIXED / CONFIRMED | Service poll reconciles on due locks (`UsageMonitorService.kt:176-179`, `UsageMonitorWatchdog.kt:17-41`); unused `onTick` in `UsageMonitorService.kt` is dead | P3 / P7 |
| **H4** | PARTIALLY FIXED / CONFIRMED | `ReleasePending` & retry alarms scheduled (`TimeWindowScheduler.kt:208,224`); hash request code used (`TimeWindowScheduler.kt:183`); cancellation on removed targets missing (`TimeWindowScheduler.kt:247`) | P2 |
| **H5** | ALREADY FIXED | Both `AlarmReceiver.kt:37,64` and `SystemEventsReceiver.kt:37,40` use `goAsync()` and `AtomicBoolean` exactly-once finish | P1 |
| **H6** | ALREADY FIXED | `UsageMonitorService.start` has try-catch (`UsageMonitorService.kt:52-57`); `monitorLoop` has try-catch with exponential backoff (`UsageMonitorService.kt:195-206`) | P1 |
| **H7** | CONFIRMED | Clock tampering / revocable Usage Access. In-app navigation/intent to settings missing. Options documented below per decision D4 | P0 / D4 |
| **M1** | ALREADY FIXED | Uninstalled packages with pending changes handled and cleared in Phase E (`LockCoordinator.kt:340-348`, `RegressionFixesTest.kt:125`) | P3 |
| **M2** | CONFIRMED | Return value of `freezer.freeze(pkg)` ignored in Phase F and limit lock (`LockCoordinator.kt:386,518`) | P3 |
| **M3** | ALREADY FIXED | `DataStoreLockStateStore.recordUnlockFailure` creates a `ReleasePending` record when record is null (`DataStoreLockStateStore.kt:142-149`, `RegressionFixesTest.kt:120`) | P3 |
| **M4** | CONFIRMED | `UsageStatsReader.kt:37,68` queries 48h event lookback per tick; `UsageMonitorService.kt:167` calls `reschedule()` per target inside loop | P5 |
| **M5** | PARTIALLY FIXED / CONFIRMED | `resolveProtected` cached in `_cachedProtectedPackages` (`MainViewModel.kt:96,149`); but `checkStatus()` runs in `MainViewModel.kt:98` init on Main | P5 |
| **M6** | ALREADY FIXED | `UsageSnapshotStore.kt:57-78` uses `mapNotNull` with try-catch skipping malformed lines, tested in `RegressionFixesTest.kt:123` | P5 |
| **M7** | ALREADY FIXED / PARTIALLY IMPLEMENTED | Safe calls on `activityInfo` (`DeviceCriticalPackageResolver.kt:122,166`); launchable system apps allowed (`DeviceCriticalPackageResolver.kt:148-155`); UI picker toggle planned for P5 | P5 |
| **M8** | ALREADY FIXED | `SettingsRepository.setTargetsAndPending` executes single DataStore edit transaction (`SettingsRepository.kt:76-84`, `LockCoordinator.kt:361`, `RegressionFixesTest.kt:124`) | P3 |
| **M9** | ALREADY FIXED | `getPackageInstallStatus` returns `PackageInstallStatus.Unknown` on exception (`DeviceDpmGateway.kt:238-240`), `isPackageInstalled` returns false (`DeviceDpmGateway.kt:283`) | P3 |
| **B1** | ALREADY FIXED | Hardcoded `org.gradle.java.home` and proxy removed (`gradle.properties:1-6`) | P7 |
| **B2** | ALREADY FIXED | `compileSdk = targetSdk = minSdk = 34` (`app/build.gradle.kts:22,27,28`); `scripts/check-environment.sh:56` checks SDK 34 | P7 |
| **B3** | ALREADY FIXED / FALSE | `app/proguard-rules.pro` exists; `AGENTS.md` exists; `keystore.properties` is optional for unit tests (`app/build.gradle.kts:37,49`) | P7 |
| **B5** | CONFIRMED | Stale "Stage 1" comment in `app/src/main/res/xml/device_admin.xml:4-5` | P7 |
| **B6** | CONFIRMED | Dead `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` check in `app/src/main/java/com/limiter/app/ui/theme/Theme.kt:24` | P7 |
| **L1** | PARTIALLY FIXED / CONFIRMED | Dead code `onTick` (`LockCoordinator.kt:98`), `getAllFrozenPackages` (`DeviceDpmGateway.kt:286`); `ensureRecord`/`markUnlockedIfExpired`/`recordReleasePending` already deleted | P7 |
| **L2** | CONFIRMED | Repeated unfreeze block across Phase C/D/G (`LockCoordinator.kt:157,228,410`); nullable `LimiterApp.lockCoordinator` (`LimiterApp.kt:81`); empty catch blocks (`MainActivity.kt:63,110,125`) | P7 |
| **L3** | CONFIRMED | Hardcoded English feedback messages in `MainViewModel.kt:195,245-267,314-366` | P4 / P6 |
| **D1** | CONFIRMED | README test count outdated; duplicate documentation across README and FINAL_README | P7 |
| **T1** | PARTIALLY FIXED / CONFIRMED | Intent gate, scheduler codes, resolver tested in `RegressionFixesTest.kt`; service loop and receiver completion tests still missing | P7 |

## 2. H7 Options (Clock Tampering & Device Owner Time Policy)
Per Decision D4, only describing options without implementation:
1. **DevicePolicyManager Time Restrictions (DO):** Device Owner can call `dpm.setAutoTimeRequired(adminComponent, true)` to enforce network time sync and prevent manual clock changes in Android Settings.
2. **User Restriction `DISALLOW_CONFIG_DATE_TIME`:** Device Owner can set `UserManager.DISALLOW_CONFIG_DATE_TIME` via `dpm.addUserRestriction`, completely disabling user access to manual time adjustments.
3. **Monotonic Boot-Time (Elapsed Realtime):** Track elapsed realtime duration alongside wall-clock time; if elapsed delta is positive while epoch time moves backwards, flag clock tampering and retain active locks.

## 3. Deadline Question Investigation
- **Phone Time & Epoch:** `Tue Oct 13 00:34:23 +0330 2026` -> epoch seconds `1791839063`.
- **Target Deadline:** `com.dv.adm` stored deadline `1792244640000` ms = `1792244640` epoch s (`2026-10-17 17:14:00 +0330`).
- **Comparison:** `phone epoch (1791839063) < stored deadline (1792244640)` (difference is ~405,577 s = 4.7 days).
- **Classification:** **Deadline-computation bug / time manipulation residue**.
- **Root Cause Evidence:** `dumpsys alarm` contains historical snapshots showing the device RTC was previously set to `rtc=2026-10-17 18:35:44.224` during earlier test runs (`#1: Reason=alarm_cancelled elapsed=-5h58m47s289ms rtc=2026-10-17 18:35:44.224`). When the lock was registered under that clock, `TimeMath.nextOccurrence` legitimately returned `2026-10-17 17:14`. When the clock was subsequently reset to Oct 12/13, `LockStateStore.lockUntilAtLeast` monotonicity preserved the Oct 17 deadline without shortening.

## 4. Expected vs Actual Lock Deadline Behavior

Testing hide/reveal window calculations (`TimeMath.nextOccurrence` & `SelfLockPolicy.isInsideWindow` in `Asia/Tehran`):

| Case | Configuration & Now | Expected `lockUntil` | Actual Code Output | Conformance |
|---|---|---|---|---|
| **a** | hide 17:10 (1030), reveal 17:14 (1034), now 17:10:30 | today 17:14:00 | today 17:14:00 | MATCH |
| **b** | hide 17:10 (1030), reveal 17:14 (1034), now 17:13:59 | today 17:14:00 | today 17:14:00 | MATCH |
| **c** | hide 17:10 (1030), reveal 17:14 (1034), now 17:14:00 | Not locked; next hide tomorrow 17:10 | Not locked (`isInsideWindow=false`); next hide tomorrow 17:10:00 | MATCH |
| **d1** | overnight hide 21:30 (1290), reveal 07:00 (420), now 22:00 | tomorrow 07:00:00 | tomorrow 07:00:00 | MATCH |
| **d2** | overnight hide 21:30 (1290), reveal 07:00 (420), now 03:00 | today 07:00:00 | today 07:00:00 | MATCH |
| **e** | window start exactly at now (17:10:00) | today 17:14:00 | today 17:14:00 | MATCH |
| **f** | timezone change between schedule and fire | Target minute evaluated in active ZoneId; lock monotonically maintained | Evaluated dynamically using injected ZoneId | MATCH |

Rule check: In all cases on a steady clock, `lockUntil - now <= 24h`.

## 5. Cause of Delayed `ACTION_HIDE` at 17:17:57
- `SCHEDULE_EXACT_ALARM` was not granted on the device (`canScheduleExactAlarms() == false`).
- `TimeWindowScheduler.scheduleAlarm` fell back to `scheduleInexact` via `AlarmManager.setAndAllowWhileIdle`.
- Inexact alarms in Android 14/15 and MIUI PowerKeeper are batched and delayed during idle/standby states (`dumpsys alarm` records `app_standby`, `device_idle`, and `battery_saver` deferrals). The alarm intended for 17:10 was dispatched after a ~7.9 minute system batching delay at 17:17:57.
