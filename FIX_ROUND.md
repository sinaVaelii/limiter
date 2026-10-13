# FIX_ROUND.md — Limiter Debug & Change Round (Flash-Optimized Master Prompt)

You are working on **Limiter**, an offline Android app (Kotlin, Compose, Device Owner, `com.limiter.app`) that limits per-app usage on a Xiaomi Redmi 12 (Android 15, MIUI).
**Current state:** The project builds, passes its tests, and locks apps on the real phone.
**Your job in this round:** ONLY (a) verify and fix real bugs, (b) implement the exact changes listed below, and (c) polish UI/UX.
**Strict Forbidden Actions:** NO rewrites. NO new architecture. NO new features beyond this file.

**Language Rules:**
- **Talk to me (the user) ONLY in Persian.**
- Write all code, code comments, logs, git commit messages, `STATE.md`, and `reports/*.md` in **English**.
- All user-facing app strings MUST be in `strings.xml` in both **Persian (default `values/strings.xml`)** and **English (`values-en/strings.xml`)**.

---

## 0. EXECUTION PROTOCOL (READ BEFORE DOING ANYTHING)

1. **SINGLE PHASE EXECUTION:** I will tell you `"Run phase Px"`. You must execute **ONLY phase Px**. When phase Px is done, write the report, update `STATE.md`, commit locally, and **STOP IMMEDIATELY**. Never start the next phase automatically.
2. **STARTUP SEQUENCE (Do these 4 reads in exact order at the start of every run):**
   - Step 0.1: Read `AGENTS.md` Sections A–E only. (Ignore legacy spec sections unless a task explicitly names one).
   - Step 0.2: Read `STATE.md` (if missing, create it using the template in §7).
   - Step 0.3: Read `reports/triage.md` (if it exists).
   - Step 0.4: Read `FIX_ROUND.md` Sections §1–§4 and **ONLY** the specific phase `Px` requested. Do NOT read old `reports/reportfor_*.md` files.
3. **PRECEDENCE:** If any rule conflicts, **`FIX_ROUND.md` overrides `AGENTS.md`**. Never skip Gradle builds or unit tests. If JDK 17, SDK 34, or Gradle fails due to environment issues, **STOP** and output the exact terminal commands I need to run. Never ask for sudo/passwords.

---

## 1. NON-NEGOTIABLE ENGINEERING RULES

- **R1 (Inspect Before/After Edit):** Always find exact file names and line numbers using `grep -rn` first. Read the entire file or class before editing. After editing, view the modified lines again to verify syntax and imports. Never guess file names, method signatures, or line numbers.
- **R2 (Evidence & Status Labels):** Every claim in your report must include a command output, test name, or `file:line`. Use ONLY these exact status labels: `COMPILED`, `TESTED (JVM)`, `VERIFIED ON DEVICE`, or `NOT RUN`. Never write "should work" or "probably fixed".
- **R3 (Minimal Local Changes & Default Params):** Fix bugs locally. Do not rename/move classes, do not change package structure, and do not reformat unrelated lines. Preserve the architecture: `DpmGateway` interface + fakes, `LockCoordinator` as sole owner of lock state, and pure policy classes (`SettingsPolicy`, `TimeMath`, `PollPlanner`, self-lock policy). **CRITICAL:** Whenever you add a new parameter to an existing function or constructor (e.g., a `CoroutineDispatcher` or boolean flag), **ALWAYS give it a default value** so existing callers and tests compile without breaking!
- **R4 (Zero Data Format Changes):** NEVER change existing pipe/tilde text codecs or DataStore keys (`limiter_lock_state.preferences_pb`, `limiter_settings.preferences_pb`). Real user data lives on the phone. Any new persisted field must be optional with a safe fallback default. Temporary UI states (like session toggles) must stay strictly in-memory (`ViewModel` state).
- **R5 (Testing Protocol):**
  - Every bug/behavior fix requires a unit test that fails before the fix and passes after.
  - Never delete or weaken existing tests.
  - **Authorized Test Updates:** If a task explicitly changes a product rule (e.g., P4 limits/self-lock) or moves synchronous calls to background coroutines (e.g., P5 dispatchers), you ARE authorized to update the affected test assertions or add `TestDispatcher` / `advanceUntilIdle()` synchronization. List every updated test and the reason in the phase report.
  - While coding, run ONLY targeted tests: `./gradlew testDebugUnitTest --offline --tests "*ClassName*"`
  - Run the full test suite (`./gradlew testDebugUnitTest --offline`) and `./gradlew assembleDebug --offline` **ONLY ONCE at the very end of the phase**.
  - Do NOT run `clean` or `lint`. If the total test count decreases, STOP and report.
- **R6 (Two-Strikes Circuit Breaker):** If a code change fails to compile or breaks a test **twice in a row**, STOP looping. Restore only the file(s) you touched in that step (`git checkout -- <file>`) and report the exact compiler/test error to me.
- **R7 (Git Hygiene):**
  - Before starting a phase, run `git status --short`. If dirty, run `git add -A && git commit -m "checkpoint before <phase>"`.
  - At the end of the phase (after tests and `assembleDebug` pass), commit locally: `git add -A && git commit -m "<phase>: <short summary>"`.
  - NEVER push, NEVER force-checkout the whole repo without checking, NEVER rewrite git history.
- **R8 (Scope Guard):** At the end of the phase, run `git diff --stat HEAD~1`. Every modified file must be listed in your report with a 1-line justification. Revert any file modified by accident.
- **R9 (Strict Report Length):**
  - `reports/reportfor_<phase>.md`: Maximum **60 lines**. Follow §7 template.
  - `STATE.md`: Maximum **40 lines**. Follow §7 template.
- **R10 (Security & Recovery Invariants):**
  - NO `INTERNET` permission. NO `QUERY_ALL_PACKAGES` (unless proven strictly necessary).
  - Keep `allowBackup=false` and `AlarmReceiver` not exported (`android:exported="false"`).
  - Self-protection uses ONLY `setUninstallBlocked` + `setUserControlDisabledPackages`.
  - NEVER add `DISALLOW_DEBUGGING_FEATURES`, `DISALLOW_FACTORY_RESET`, `DISALLOW_SAFE_BOOT`, or `DISALLOW_APPS_CONTROL`.
  - Keep `adb shell dpm remove-active-admin` recovery working (debug build must remain `testOnly`). Never build or install a release APK.
- **R11 (No Stranded Locks):** No code path may leave an app hidden or suspended past its deadline.

---

## 2. PHONE SAFETY PROTOCOL (ADB RULES)

- **Target Device:** Run `adb devices` and use the attached device (serial `4a952d897d79`).
- **Default Mode is READ-ONLY:** Allowed read-only commands: `getprop`, `date`, `dumpsys` (`package`, `device_policy`, `alarm`, `activity services`), `pm list packages`, `appops get`, `logcat -d`, `exec-out run-as com.limiter.app cat <file>`.
- **Forbidden ADB Actions:** NEVER uninstall, force-stop, clear data, reboot, change system time, change permissions/alarms, unhide/unsuspend packages, modify DataStore, or launch `MainActivity` unless the current phase explicitly instructs you to run that exact command.
- **Mandatory Pre-Install Backup (Only in phases that authorize `adb install`):**
  1. Backup DataStore binary files with a timestamp (never overwrite old backups):
     - `adb exec-out run-as com.limiter.app cat files/datastore/limiter_lock_state.preferences_pb > reports/evidence/pre_lock_state_<timestamp>.pb`
     - `adb exec-out run-as com.limiter.app cat files/datastore/limiter_settings.preferences_pb > reports/evidence/pre_settings_<timestamp>.pb`
  2. Record `adb shell date`, `adb shell "date +%s"`, and `adb shell dumpsys package <pkg> | grep -E "suspended=|hidden=|installed="` for all 6 target packages:
     - `ir.nasim`
     - `net.iGap`
     - `org.chromium.webapk.a5b83bbe72c293c31_v2`
     - `com.supercell.clashroyale`
     - `ir.ilmili.telegraph`
     - `com.dv.adm`
  3. Verify APK signature (`apksigner verify --print-certs`). SHA-256 MUST equal `345444081af98f194585113aa3e3d493adcd100ad657addc6cd76506fbab79dc`. If mismatch, STOP.
  4. Install with `adb install -r -t <apk>`.
  5. Repeat step 2 after install and verify `adb shell dpm list-owners` still shows `com.limiter.app`.

---

## 3. LOCKED-IN HUMAN DECISIONS

- **D1 (SDK Baseline):** `compileSdk = 34`, `targetSdk = 34`, `minSdk = 34`. (Phone runs Android 15 / API 35 and works).
- **D2 (Service Reconcile Exception):** `UsageMonitorService` IS allowed on each poll tick to call `LockCoordinator.reconcile()` ONLY when a lock deadline (`untilEpochMillis <= now`) or retry (`nextUnlockRetryAtMillis <= now`) is due. The service must NEVER call freeze/unfreeze directly.
- **D3 (Exact Alarm Grant):** In P2, you are authorized to run `adb shell appops set com.limiter.app SCHEDULE_EXACT_ALARM allow`.
- **D4 (Clock Tampering - H7):** Design decision reserved for human. Describe options in P0 triage only; do NOT implement code for H7.
- **D5 (Product Rules - P4):** Max daily limit = **720 minutes (12h)**. Max limit increase per edit = **+30 minutes** (compared to current active limit, not pending). Self-lock blocks ONLY loosening; viewing the app list and adding NEW target apps is allowed during self-lock.

---

## 4. ISSUE CATALOG (CLAIMS TO VERIFY IN P0 & FIX IN P1–P7)

*Note: This review was written against an older snapshot. Verify every item in code first.*

- **H1:** `MainActivity.handleIntent` (~lines 47–61) is exported and processes intent extras (`add_target`, `limit`, `hide`, `reveal`), allowing external bypass of self-lock.
- **H2:** Self-lock is checked only in `MainViewModel`; `LockCoordinator.tryEditConfig / tryRemoveConfig` lack self-lock enforcement. `_currentMillis` only ticks on `SelfLockScreen`/`onResume`. `SelfLockScreen` calls `System.currentTimeMillis()` instead of `TimeSource`.
- **H3:** `onTick()` is dead; `UsageMonitorService` never reconciles expired locks on poll ticks.
- **H4:** `TimeWindowScheduler` schedules no alarm for `ReleasePending`; `REQUEST_RETRY`/`ACTION_RETRY` not scheduled; hide alarm request codes depend on map iteration order and removed targets are not cancelled.
- **H5:** `AlarmReceiver` and `SystemEventsReceiver` launch coroutines without `goAsync()`.
- **H6:** `startForegroundService` calls in `LimiterApp.onCreate`, `MainActivity.onStart`, and receivers are unguarded (`ForegroundServiceStartNotAllowedException`). `UsageMonitorService.monitorLoop` lacks a `try/catch` inside the loop.
- **H7:** Clock tampering & revocable Usage Access (Human decision D4 — document only in P0).
- **M1:** Pending changes for uninstalled packages are never applied/cleared in `LockCoordinator` Phase E (~line 215).
- **M2:** `freezer.freeze()` return result is ignored; lock is saved as verified even if freeze fails.
- **M3:** `DataStoreLockStateStore.recordUnlockFailure` returns early (`?: return@edit`) if no record exists (~line 142), dropping failure history.
- **M4:** `UsageStatsReader` queries 48h of events per target on the Main thread; service calls `reschedule()` per over-limit target on every tick.
- **M5:** `MainViewModel` calls `resolveProtected()` (heavy IPC) on the Main thread inside every `combine` emission (every second), and runs `checkStatus()` in `init` on Main.
- **M6:** `UsageSnapshotStore.decodeSnapshots` uses `require(...)`, so a single corrupt line crashes the UI flow.
- **M7:** `DeviceCriticalPackageResolver` has NPE risk on `it.activityInfo.packageName` and excludes all `FLAG_SYSTEM` apps (blocking preinstalled Chrome/YouTube on Xiaomi).
- **M8:** Non-atomic `setTargets` + `setPending` across stores in Phase E.
- **M9:** `isPackageInstalled` returns `true` on unexpected exceptions instead of `Unknown`.
- **B1:** `gradle.properties` has hard-coded `org.gradle.java.home` and proxy `10.97.201.106:8080`.
- **B2:** SDK version mismatch across docs/scripts (`check-environment.sh`).
- **B3:** `keystore.properties` required even for debug/unit tests; stale `AGENTS.md`/`proguard-rules.pro` claims.
- **B5:** `device_admin.xml` has empty `uses-policies` and stale "Stage 1" comment.
- **B6:** Redundant `SDK_INT >= TIRAMISU/S` checks when `minSdk = 34`.
- **L1:** Dead code (`onTick`, `getAllFrozenPackages`, `ACTION_RETRY`, `ensureRecord`, `markUnlockedIfExpired`, `recordReleasePending`, unused `Uri` imports).
- **L2:** Duplicated `isInsideWindow`, 4x duplicated unfreeze block, non-null `lockCoordinator` marked nullable, empty `catch (_: Exception) {}` in `MainActivity`.
- **L3:** Hard-coded English feedback strings in `MainViewModel`.
- **L4:** Custom text codecs instead of JSON — **KEEP AS IS (Rule R4)**.
- **D1 (Docs):** README test count and public-use wording outdated; duplication with `FINAL_README`.
- **T1:** Missing JVM unit tests for service tick, scheduler request codes/cancellation, and receivers.

---

## 5. STEP-BY-STEP PHASE INSTRUCTIONS

### PHASE P0 — Triage (READ-ONLY; NO CODE EDITS)
1. Run `git status` and `git log --oneline -n 5` to record the starting commit hash.
2. Inspect the codebase for every ID in §4 (`H1–H7`, `M1–M9`, `B1–B6`, `L1–L3`, `T1`). Classify each as `CONFIRMED`, `ALREADY FIXED`, or `FALSE` with exact `file:line` evidence. Write one compact table in `reports/triage.md`: `ID | Status | Evidence (file:line) | Planned Phase`. For `H7`, add 3–5 lines of options only.
3. **Phone Deadline Check (Read-Only):** Run `adb shell date`, `adb shell "date +%s"`, and `adb shell dumpsys alarm | grep -E "com.limiter.app"`. Compare phone epoch seconds against `com.dv.adm` stored deadline `1792244640` (`1792244640000` ms = 2026-10-17 17:14 +03:30, rule is hide 17:10 / reveal 17:14):
   - If phone epoch `< 1792244640`: multi-day deadline calculation bug.
   - If phone epoch `>= 1792244640`: lock expired without reconciliation.
4. Inspect `TimeMath.kt` and `LockCoordinator.kt` for window deadline calculation. Check cases (a) through (f) in `Asia/Tehran` (same-day window 17:10–17:14, overnight window 21:30–07:00, exact boundary `now == 17:10:00`, timezone shift). Any window `lockUntil - now > 24h` is a bug.
5. Check `dumpsys alarm` logs for why `ACTION_HIDE` for `com.dv.adm` fired at `17:17:57`.
6. Update `STATE.md`, write `reports/reportfor_P0.md`, and **STOP**.

---

### PHASE P1 — Entry Point Security & Crash Safety (H1, H5, H6)
1. **Task 1.1 (H1 - Intent Extras):**
   - Open `MainActivity.kt`. Remove the intent extras handling (`add_target`, `limit`, `hide`, `reveal`) from `app/src/main/`.
   - If needed for debug testing, move it to a debug-only helper or guard it strictly with `if (BuildConfig.DEBUG)` AND route it through the standard self-lock/limit policy checks (never bypass self-lock).
   - Inspect `AndroidManifest.xml` and list every component's `android:exported` value in your report. Confirm only `MainActivity` is exported (for `LAUNCHER`).
2. **Task 1.2 (H5 - Receiver `goAsync`):**
   - In `AlarmReceiver.kt` and `SystemEventsReceiver.kt`, call `val pendingResult = goAsync()` once in `onReceive`.
   - Launch the coroutine on an application-scoped `CoroutineScope(SupervisorJob() + Dispatchers.Default)`.
   - Wrap the work in `withTimeout(8_000)` and `try / catch / finally`, ensuring `pendingResult.finish()` is called **exactly once** inside `finally` (use an `AtomicBoolean` guard if needed).
   - Write JVM unit tests verifying `finish()` is called once on normal completion, on exception, and on timeout.
3. **Task 1.3 (H6 - FGS & Monitor Loop Safety):**
   - Wrap every `startForegroundService(...)` call (`LimiterApp.onCreate`, `MainActivity.onStart`, receivers) in a `try / catch (e: Exception)` that logs a warning with tag `"Limiter"` (catching `ForegroundServiceStartNotAllowedException` and general exceptions without crashing).
   - In `UsageMonitorService.monitorLoop`, wrap the body of the `while` loop in `try / catch (e: CancellationException) { throw e } catch (e: Exception) { Log.e("Limiter", ...); delay(backoffMillis) }` so the loop never dies silently.
   - Add a JVM unit test for the monitor loop exception recovery.
4. **Verify & Stop:** Run `./gradlew testDebugUnitTest --offline` and `./gradlew assembleDebug --offline`. Write `reports/reportfor_P1.md`, update `STATE.md`, commit (`P1: secure entry points and guard receivers/FGS`), and **STOP**.

---

### PHASE P2 — Time Windows & Exact Alarms (Deadline Bug, H4, D3)
1. **Task 2.1 (Deadline Bug):**
   - Based on P0 findings: if `TimeMath` / `LockCoordinator` computes window `lockUntil` > 24h in the future, write a failing unit test `..._reproducesDeadlineBug`, then fix the calculation so same-day and overnight windows always compute the nearest upcoming reveal time while keeping `lockUntil` monotonic. (If P0 proved no calculation bug, add a regression test for expired-lock reconciliation).
2. **Task 2.2 (H4 - `TimeWindowScheduler`):**
   - Schedule an alarm for `ReleasePending` records.
   - Schedule `ACTION_RETRY` (with stable `REQUEST_RETRY`) whenever any record has `nextUnlockRetryAtMillis != null`.
   - Generate deterministic request codes for hide/unlock alarms using `Objects.hash(packageName, action)` (never use map index).
   - Track previously scheduled package alarms and actively cancel alarms when a target is removed or its window changes.
   - Write JVM unit tests verifying: deterministic request codes, alarm cancellation on target removal, and retry scheduling.
3. **Task 2.3 (Exact Alarm UI & ADB Grant):**
   - In the UI status card, when `canScheduleExactAlarms()` is `false`, display a button that launches `Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))` and re-check status in `onResume`.
   - Run on phone: `adb shell appops set com.limiter.app SCHEDULE_EXACT_ALARM allow` followed by `adb shell appops get com.limiter.app SCHEDULE_EXACT_ALARM`.
4. **Verify & Stop:** Run full unit tests and `assembleDebug`. Write `reports/reportfor_P2.md`, update `STATE.md`, commit (`P2: fix scheduler alarms and exact alarm flow`), and **STOP**.

---

### PHASE P3 — Lock/Unlock State Machine Correctness (H3, M1, M2, M3, M8, M9)
1. **Task 3.1 (H3/D2 - Poll Tick Reconcile):**
   - In `UsageMonitorService` poll tick: check if any stored lock record has `untilEpochMillis <= now` or `nextUnlockRetryAtMillis <= now`. If true, call `lockCoordinator.reconcile()` once and `scheduler.reschedule()` once. Never call `freeze`/`unfreeze` directly from the service. Add unit tests (0 calls when nothing due; 1 call when due).
2. **Task 3.2 (M2 - Check Freeze Result):**
   - In `LockCoordinator`, inspect the return value of `freezer.freeze(pkg)`. If it fails, do NOT mark the lock as verified; record a retry/failure state with bounded backoff and log it. Add unit test.
3. **Task 3.3 (M3 - `recordUnlockFailure` Missing Record):**
   - In `DataStoreLockStateStore.recordUnlockFailure`, if no record exists for the package, create a minimal valid record using existing codec fields instead of returning early. Add unit test.
4. **Task 3.4 (M9 & Stuck-Lock Fallback):**
   - Ensure `isPackageInstalled` returns `PackageInstallStatus.Unknown` (not `true`) on unexpected exceptions.
   - In `LockCoordinator` release logic: after **3 consecutive `Unknown` install checks** for a lock created by Limiter, call `restoreVisibility` + `restoreSuspension` anyway, and commit `recordUnlock` only if both return `Ok`. Add unit tests for `Unknown` count 2 vs 3.
5. **Task 3.5 (M1 & M8 - Uninstalled Pending Changes):**
   - In `LockCoordinator` Phase E, clear/apply pending changes for packages confirmed as `NotInstalled` (keep them if `Unknown`). If `setTargets` + `setPending` can be combined safely without changing DataStore keys, do so; otherwise document in report.
6. **Verify & Stop:** Run full unit tests and `assembleDebug`. Write `reports/reportfor_P3.md`, update `STATE.md`, commit (`P3: harden lock coordinator and service reconciliation`), and **STOP**.

---

### PHASE P4 — Product Rules: Limits & Self-Lock (D5, H2, L3)
**Guardrail:** Implement rules in pure policy classes (`SettingsPolicy` / `SelfLockGate`). Update existing policy unit tests whose assertions tested old rules, and list those updated tests in your report.
1. **Task 4.1 (Rule A - Usage Limit Bounds):**
   - Set constants: `MAX_DAILY_LIMIT_MINUTES = 720` (12h), `MAX_LIMIT_INCREASE_MINUTES = 30`, `MIN_DAILY_LIMIT_MINUTES = 1`.
   - New target: limit must be in `1..720` (applied immediately).
   - Decreasing an existing limit: applied immediately.
   - Increasing an existing limit (`delta = proposed - currentActiveLimit`):
     - Compare ONLY against `currentActiveLimit` (never against a pending limit, preventing cumulative +30 stacking).
     - If `proposed > 720` or `delta > 30`: reject immediately with a specific validation error.
     - If `1 <= delta <= 30`: queue as a delayed pending change (using existing delay mechanism).
   - Clamp legacy stored limits `> 720` (such as `ir.ilmili.telegraph` at 1000m) to `min(stored, 720)` in policy/reader and tighten on next `reconcile()`. Keep `com.supercell.clashroyale` pending `100 -> 120` valid.
2. **Task 4.2 (Rule B - Self-Lock Gate & H2 Fix):**
   - While self-lock is active:
     - **ALLOWED:** Viewing dashboard, viewing app picker, adding a **NEW** target app (an app not currently in targets), decreasing a limit, cancelling a pending loosening change.
     - **BLOCKED:** Removing a target, increasing a limit, loosening a hide/reveal window.
   - Enforce this single gate in BOTH `MainViewModel` AND `LockCoordinator.tryEditConfig / tryRemoveConfig` using `TimeSource.now()`.
   - Replace `System.currentTimeMillis()` in `SelfLockScreen` with `TimeSource`. Drive self-lock UI state from a ticker using `TimeSource` so dashboard and self-lock screen update immediately at window boundaries.
   - In the Picker during self-lock: show already-targeted apps as disabled ("Already limited") so they cannot be re-added, and show a confirmation dialog before adding a new app during self-lock (`strings.xml` in `fa` and `en`: `"این کار تا پایان قفل خودکار قابل برگشت نیست"`).
   - Replace hard-coded English feedback messages in `MainViewModel` (L3) with localized string resources (`fa` + `en`).
3. **Verify & Stop:** Write comprehensive unit tests for all bounds (`0`, `1`, `720`, `721`, `+30`, `+31`, cumulative cap, `1000->720` clamp, self-lock gate in ViewModel and Coordinator). Run full unit tests and `assembleDebug`. Write `reports/reportfor_P4.md`, update `STATE.md`, commit (`P4: enforce 720m/30m limit rules and unified self-lock gate`), and **STOP**.

---

### PHASE P5 — Performance, Threading & Fault Tolerance (M4, M5, M6, M7)

**CRITICAL FLASH GUARDRAILS FOR P5 (READ CAREFULLY TO AVOID BREAKING TESTS):**
- **Default Parameters Required (R3):** Whenever you add a `CoroutineDispatcher` or a `Boolean` parameter to a class constructor or method (`UsageStatsReader`, `MainViewModel`, `DeviceCriticalPackageResolver.isEligibleTarget`), **YOU MUST PROVIDE A DEFAULT VALUE** (e.g., `ioDispatcher: CoroutineDispatcher = Dispatchers.IO`, `includeSystemApps: Boolean = false`). This guarantees all existing callers and unit tests compile without modification!
- **No DataStore Changes (R4):** For M7 ("Show system apps" toggle and its first-time confirmation dialog), store `showSystemApps: Boolean` and `hasConfirmedSystemAppsOnce: Boolean` **STRICTLY IN MEMORY** inside `MainViewModel` / Compose UI state. Do NOT touch DataStore or codecs!
- **Test Synchronization (R5):** When moving `MainViewModel` (`resolveProtected`, `checkStatus`) or `UsageStatsReader` work onto background dispatchers, existing tests in `MainViewModelTest` may need `UnconfinedTestDispatcher` passed into the constructor or `advanceUntilIdle()` before assertions. You are explicitly authorized to make that test dispatcher adjustment—just list the test names in `reports/reportfor_P5.md`.

**Execute P5 in 4 atomic steps (run targeted tests after each step!):**

1. **Step 5.1 — Fix M6 (`UsageSnapshotStore.decodeSnapshots` Tolerance):**
   - Find `decodeSnapshots` in `UsageSnapshotStore.kt`.
   - Replace `require(...)` with tolerant line-by-line parsing (matching the pattern in `Codec`): if a line is malformed or corrupt, log a warning and skip that single line (`continue`), returning all valid lines.
   - Add a unit test in `UsageSnapshotStoreTest` (or existing codec test class) passing a multi-line string containing 1 corrupt line and 2 valid lines; assert no exception is thrown and the 2 valid snapshots are returned.
   - Run targeted test: `./gradlew testDebugUnitTest --offline --tests "*Snapshot*"`

2. **Step 5.2 — Fix M4 (`UsageStatsReader` Off-Main & `UsageMonitorService` Single Reschedule):**
   - Open `UsageStatsReader.kt`. Add `private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO` with a default value to its constructor. Wrap the `usageStatsManager.queryEvents(...)` calculation inside `withContext(ioDispatcher)`.
   - Inspect `UsageCalculator.kt` and `UsageCalculatorTest.kt`. Check how far back events are needed (e.g., since start of local day / midnight rollover). Narrow the 48h query window ONLY if 100% of `UsageCalculatorTest` stays green, and add a code comment explaining the window duration.
   - Open `UsageMonitorService.kt`. In the poll tick, instead of calling `scheduler.reschedule()` inside the per-target loop for every over-limit package, track a boolean `var needsReschedule = false` during the tick and call `scheduler.reschedule()` **at most once at the end of the tick** when state/plan actually changed.
   - Run targeted tests: `./gradlew testDebugUnitTest --offline --tests "*Usage*"`

3. **Step 5.3 — Fix M5 (`MainViewModel` Protected-Set Caching & Off-Main Init):**
   - Open `MainViewModel.kt`. Add `private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default` (with default value) to `MainViewModel` constructor.
   - Create an in-memory cached state (e.g., `private val _protectedPackages = MutableStateFlow<Set<String>>(emptySet())`).
   - Write a function `refreshProtectedPackages()` that launches on `viewModelScope` using `withContext(defaultDispatcher)` to call `criticalPackageResolver.resolveProtected()` and updates `_protectedPackages`.
   - Remove `resolveProtected()` from inside the `combine(...)` block! Inside `combine(...)`, read from the cached `_protectedPackages` flow instead so IPC is never called on the Main thread or on every 1-second tick.
   - Call `refreshProtectedPackages()` and `checkStatus()` asynchronously on `defaultDispatcher` during startup, on `onResume()`, and when a package broadcast occurs.
   - Run targeted tests: `./gradlew testDebugUnitTest --offline --tests "*MainViewModel*"` (if a test needs `UnconfinedTestDispatcher` injected into `MainViewModel`, pass it cleanly).

4. **Step 5.4 — Fix M7 (`DeviceCriticalPackageResolver` Null-Safety & System Apps Toggle):**
   - Open `DeviceCriticalPackageResolver.kt`. Change any unsafe `it.activityInfo.packageName` access to null-safe `it.activityInfo?.packageName`.
   - In `isEligibleTarget(pkgInfo, ..., includeSystemApps: Boolean = false)`:
     - Keep all critical/protected package exclusions 100% intact (Dialer/Phone, SMS, Settings, Launcher, enabled IMEs, enabled Accessibility Services, emergency packages, GMS/system-critical, and `com.limiter.app` itself MUST ALWAYS return `false`).
     - If an app has `ApplicationInfo.FLAG_SYSTEM` (and is not an updated system app if previously allowed): return `false` when `includeSystemApps == false`, and return `true` when `includeSystemApps == true` PROVIDED it has a launchable activity and is NOT in the protected set.
   - Add unit tests in `DeviceCriticalPackageResolverTest` (using fakes) verifying: (a) null `activityInfo` does not throw NPE, (b) launchable system app (like Chrome/YouTube) is excluded when `includeSystemApps = false` and included when `includeSystemApps = true`, (c) protected system apps (Settings, Dialer, Launcher, IME) remain excluded even when `includeSystemApps = true`.
   - In `MainViewModel` and `LimiterScreen.kt` (App Picker section): add an in-memory `showSystemApps` toggle (default `false`) and `hasConfirmedSystemAppsDialog` (default `false`). The first time the user toggles "Show system apps" ON, show an `AlertDialog` warning them before turning it on. Add Persian (`values/strings.xml`) and English (`values-en/strings.xml`) strings for the toggle label and confirmation dialog.
   - Run targeted tests: `./gradlew testDebugUnitTest --offline --tests "*CriticalPackage*"`

5. **Step 5.5 — Final P5 Verification & Stop:**
   - Run full unit test suite once: `./gradlew testDebugUnitTest --offline`
   - Run debug build once: `./gradlew assembleDebug --offline`
   - Run `git diff --stat HEAD~1` to verify changed files.
   - Write `reports/reportfor_P5.md` (≤ 60 lines) and update `STATE.md` (≤ 40 lines).
   - Commit locally: `git add -A && git commit -m "P5: performance, caching, tolerant snapshots, and system apps toggle"`
   - **STOP.**

---

### PHASE P6 — UI/UX Redesign (Compose Material 3)
**Strict Rule:** Presentation and `strings.xml` ONLY. Do NOT alter `MainViewModel` business logic or policy classes.
1. Read `LimiterScreen.kt` in chunks and list all existing Composables first.
2. Redesign screens using Material 3, Light/Dark support, proper `WindowInsets`, 48dp touch targets, and full RTL support for Persian:
   - **Dashboard:** Top compact health chips (Device Owner, Usage Access, Notifications, Exact Alarm, Self-Protection) where tapping a failing chip opens its fix intent. Target cards with async cached app icon, app name, usage progress bar, state badge, and next event. FAB to add app.
   - **Add/Edit Target & Picker:** Searchable `LazyColumn` with stable keys and the "Show system apps" toggle from P5. Replace raw minute typing with duration chips (`15m`, `30m`, `1h`, `2h`, `4h`, `8h`, `12h`) + stepped hour/minute picker clamped to 12h (720m), showing the +30m increase rule helper text. Material 3 `TimePicker` for hide/reveal windows.
   - **Self-Lock Screen:** Calm neutral tone, live countdown, secondary "Add app" button, read-only limits list, and NO "unlock/snooze" buttons.
3. Run `./gradlew testDebugUnitTest --offline` and `./gradlew assembleDebug --offline`. Write `reports/reportfor_P6.md`, update `STATE.md`, commit (`P6: Material 3 UI/UX polish and RTL localization`), and **STOP**.

---

### PHASE P7 — Cleanup, Build Config & Docs (B1–B6, L1–L2, D1, T1)
1. **B2/D1:** Ensure `compileSdk = targetSdk = minSdk = 34` in `app/build.gradle.kts` and `scripts/check-environment.sh`.
2. **B1:** Remove hard-coded `org.gradle.java.home` and proxy from tracked `gradle.properties` ONLY if `./gradlew assembleDebug --offline` still succeeds with `JAVA_HOME` set (otherwise revert).
3. **B3/B5/B6:** Make unit tests run even if `keystore.properties` is absent; clean up stale comments in `device_admin.xml`; remove dead `SDK_INT >= TIRAMISU/S` branches.
4. **L1/L2:** Delete dead symbols (`onTick`, `getAllFrozenPackages`, etc.) ONLY after `grep -rn` confirms 0 usages in `src/main` and `src/test`. Log swallowed exceptions in `MainActivity`.
5. **T1 & D1 (Docs):** Add any remaining JVM unit tests for scheduler/service/receivers. Update `README.md` with the exact final test count, the `adb shell dpm remove-active-admin` recovery command, and the 720m/+30m self-lock rules.
6. Run `./gradlew testDebugUnitTest --offline` and `./gradlew assembleDebug --offline`. Write `reports/reportfor_P7.md`, update `STATE.md`, commit (`P7: config cleanup, dead code removal, and documentation`), and **STOP**.

---

## 6. HUMAN ON-DEVICE CHECKLIST (DO NOT RUN YOURSELF)
Reserved for the user after P7: install debug APK, verify Device Owner, test 1-min window on `com.dv.adm`, test self-lock rules, test intent injection rejection, reboot recovery, and verify uninstall protection.

---

## 7. MANDATORY REPORT TEMPLATES

### `reports/reportfor_<phase>.md` (Maximum 60 lines)
```markdown
# Report <phase> — <title>
Commit before/after: <hash> -> <hash>

## Done
- <ID>: <what changed> (`<file>:<line>`)

## Verified
- Status: COMPILED | TESTED (JVM) | VERIFIED ON DEVICE | NOT RUN
- Total unit tests: <before_count> -> <after_count>
- New/Updated tests: `<TestClass.testMethod>`

## Not Done / Blocked / Needs Decision
- <None item or>

## Changed Files (`git diff --stat HEAD~1`)
- `<file>`: <1-line reason>

## Risks / Phone Checks for User
- <1-2 bullets>