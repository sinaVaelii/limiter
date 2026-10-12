# FIX_ROUND.md — Limiter debug + change round (master prompt)

You are working on **Limiter**, a personal, offline Android app (Kotlin, Compose, Device Owner, `com.limiter.app`) that limits per-app usage on MY phone (Xiaomi Redmi 12, Android 15, MIUI). **The project already builds, passes its tests, and locks apps on the real phone.** This round is ONLY: (a) verify and fix real bugs, (b) the specific changes listed below, (c) UI/UX polish. **No rewrites. No new architecture. No new features beyond this file.**

Talk to me in **Persian**. Code, comments, logs, commit messages, reports in English. User-facing app strings: Persian (default) + English, in `strings.xml`.

---------------------------------------------------------------------
## 0. HOW THIS FILE IS USED (read carefully)

- This file is run **ONE PHASE PER RUN**. My message will say "Run phase Px". Do **only** that phase. When it is finished, write the report, update `STATE.md`, and **STOP**. Do not start the next phase, even if you think you have time.
- At the start of EVERY run, do these in order, and nothing else first:
  1. Read `AGENTS.md` Sections A–E only (rules and stage workflow). Do NOT read the legacy spec sections unless a task below names one.
  2. Read `STATE.md` (if missing, create it from the template in §7).
  3. Read `reports/triage.md` (exists after phase P0).
  4. Read ONLY this file's §1–§4 and the section of the phase you were asked to run.
  Do not read old `reports/reportfor_*.md` files unless a task says so.
- Precedence if documents conflict: **this file > AGENTS.md** for this round. The environment rule in AGENTS.md that lets you skip builds is **cancelled**: if JDK 17 / SDK 34 / Gradle is broken, STOP and tell me the exact commands I must run. Never ask for passwords.

---------------------------------------------------------------------
## 1. NON-NEGOTIABLE RULES

R1. **Look before you edit.** Before changing a file, `view` the whole file (or the whole relevant class). After editing, `view` the changed region again. Never edit from memory. Never invent file names, class names or line numbers: find them with `grep -rn` first.
R2. **Evidence or it did not happen.** Every claim in a report must come with a command output, a test name, or `file:line`. Use exactly three words for status: `COMPILED`, `TESTED (JVM)`, `VERIFIED ON DEVICE`. If you did not run something, write `NOT RUN`. Never write "should work".
R3. **Small, local changes.** Fix the bug where it is. Do not rename or move classes, do not change package structure, do not reformat files, do not "clean up" unrelated code. Keep the existing layering: `DpmGateway` interface + fakes, `LockCoordinator` as the single owner of lock state, pure policy classes (`SettingsPolicy`, `TimeMath`, `PollPlanner`, self-lock policy) with unit tests.
R4. **Never change persisted data formats.** The pipe/tilde text codecs and DataStore keys stay exactly as they are (real data exists on the phone). Any new field must be optional with a safe default when absent.
R5. **Tests:** every behavior fix gets a unit test that fails before the fix and passes after. Never delete or weaken an existing test. If a test must change because the requirement changed, say so explicitly in the report with the reason. While working run only targeted tests: `./gradlew testDebugUnitTest --offline --tests "*ClassName*"`. Run the **full** suite and `./gradlew assembleDebug --offline` **once, at the end of the phase**. Do not run `clean`. Do not run lint. If the total test count goes DOWN, stop and report.
R6. **Two-strikes rule.** If a fix fails (compile error or red test) twice, stop, restore the file (`git checkout -- <file>` only for files YOU changed in this phase), and report what failed. Do not loop.
R7. **Git:** the repo has history. Before starting a phase run `git status --short`. If dirty, commit locally with `git add -A && git commit -m "checkpoint before <phase>"`. After the phase passes, commit locally `<phase>: <summary>`. **Never push, never force, never rewrite history.**
R8. **Scope guard:** at the end of the phase run `git diff --stat HEAD~1` and list every changed file in the report with a one-line reason. Any file not justified by a task in this phase must be reverted.
R9. **Reports are short.** `reports/reportfor_<phase>.md`: maximum 60 lines, use the template in §7, no long tables, no test-by-test listings (only counts + the new test names). `STATE.md`: maximum 40 lines.
R10. **Do not touch** these invariants: no `INTERNET` permission; no `QUERY_ALL_PACKAGES` unless proven necessary by evidence; `allowBackup=false`; `AlarmReceiver` not exported; self-protection stays only `setUninstallBlocked` + `setUserControlDisabledPackages`; **never** add `DISALLOW_DEBUGGING_FEATURES`, `DISALLOW_FACTORY_RESET`, `DISALLOW_SAFE_BOOT`, `DISALLOW_APPS_CONTROL`; the `adb shell dpm remove-active-admin` recovery path must keep working (debug build stays `testOnly`). **Do not create or install a release build.**
R11. Locking must never strand an app: any code path that can leave a package hidden/suspended past its deadline is a bug.

---------------------------------------------------------------------
## 2. PHONE SAFETY PROTOCOL (applies whenever you use `adb`)

Device serial: use `adb devices` and pick the single attached device (previously `4a952d897d79`).

Default is **read-only**. Allowed read-only commands: `getprop`, `date`, `dumpsys` (package, device_policy, alarm, activity services), `pm list packages`, `appops get`, `logcat -d`, `exec-out run-as com.limiter.app cat <file>`.

Install is allowed **only** in phases that say so. Before ANY `adb install`:
1. Save raw DataStore files with `exec-out` (binary-safe, NOT `shell … >`):
   `adb exec-out run-as com.limiter.app cat files/datastore/limiter_lock_state.preferences_pb > reports/evidence/pre_lock_state.pb` (same for `limiter_settings.preferences_pb`). Do not overwrite older evidence files; use new names with a timestamp.
2. Save `adb shell date` and `adb shell "date +%s"` and the output of `dumpsys package <pkg> | grep -E "suspended=|hidden=|installed="` for all six targets: `ir.nasim`, `net.iGap`, `org.chromium.webapk.a5b83bbe72c293c31_v2`, `com.supercell.clashroyale`, `ir.ilmili.telegraph`, `com.dv.adm`.
3. Verify the signing certificate of the new APK equals the installed one (`apksigner verify --print-certs`, SHA-256 must be `345444081af98f194585113aa3e3d493adcd100ad657addc6cd76506fbab79dc`). If not equal: STOP.
4. Install with `adb install -r -t <apk>` (no `-d` unless I ask).
After install: repeat step 2, and `dpm list-owners` must still show the Device Owner. Report any difference.

**Never**, on the phone: uninstall, force-stop, clear data, reboot, change date/time, change alarms/permissions, unhide/unsuspend packages, or modify DataStore — **unless the phase explicitly authorizes that exact command.** Never start `MainActivity` unless the phase says so (launching the app runs `reconcile()` and can legitimately unlock expired locks; that changes what I am trying to observe).

---------------------------------------------------------------------
## 3. HUMAN DECISIONS AND AUTHORIZATIONS (already made by me)

D1. **SDK baseline:** `compileSdk = targetSdk = minSdk = 34`. Phone runs API 35 and works. Record this in `STATE.md` and fix any document that says otherwise (phase P7).
D2. **Narrow exception to AGENTS.md §17:** `UsageMonitorService` MAY, on each poll tick, **call** `LockCoordinator.reconcile()` when some lock deadline or `nextUnlockRetryAtMillis` is due. The service must never freeze/unfreeze by itself. `LockCoordinator` remains the sole owner of lock state. The call must be idempotent and must not spam DataStore writes or alarms.
D3. **Exact alarm:** the app shows a status warning because `SCHEDULE_EXACT_ALARM` is not granted. In phase P2 you may run exactly: `adb shell appops set com.limiter.app SCHEDULE_EXACT_ALARM allow` and then `adb shell appops get com.limiter.app SCHEDULE_EXACT_ALARM`.
D4. **Clock tampering (review item H7)** is a design decision, NOT yours: only describe options in the triage report; implement nothing.
D5. **New product rules (phase P4):** max daily limit **720 minutes (12 h)**; a limit may be **raised by at most 30 minutes per change**; self-lock must **not** block viewing the app list or adding NEW target apps (details in P4).

---------------------------------------------------------------------
## 4. ISSUE CATALOG (from an external code review)

**WARNING: the review may have been written against an OLDER snapshot** (it mentions `minSdk 35`, a missing `AGENTS.md`, a missing `proguard-rules.pro`, 164/188 tests; the current project has 229 tests, an `AGENTS.md`, and `minSdk 34`). Therefore **every item below is a CLAIM to verify, not a fact.** In P0 classify each as `CONFIRMED`, `ALREADY FIXED`, or `FALSE`, with `file:line` evidence.

Severity High:
- **H1** `MainActivity.handleIntent` (≈ MainActivity.kt:47-61): the activity is exported and accepts intent extras `add_target / limit / hide / reveal` → any app or `adb am start` can add targets and bypass the self-lock gate.
- **H2** Self-lock is enforced only in `MainViewModel`; `LockCoordinator.tryEditConfig / tryRemoveConfig` are unguarded. `_currentMillis` only ticks in SelfLockScreen/onResume, so self-lock status is stale on the dashboard when a window starts. `SelfLockScreen` uses `System.currentTimeMillis()` instead of `TimeSource`.
- **H3** `onTick()` is never called; the service never reconciles; unlock/retry depends only on alarms.
- **H4** `TimeWindowScheduler`: no alarm for `ReleasePending`; `REQUEST_RETRY`/`ACTION_RETRY` possibly never scheduled; hide alarms indexed by map order and never cancelled for removed targets.
- **H5** Receivers launch coroutines without `goAsync()` (`AlarmReceiver`, `SystemEventsReceiver`).
- **H6** `startForegroundService` unguarded (`LimiterApp.onCreate`, `MainActivity.onStart`, receivers) → `ForegroundServiceStartNotAllowedException`; `monitorLoop` has no try/catch → can die silently.
- **H7** Clock tampering and revocable Usage Access; no in-app way to grant permissions (ADB only). → decision item D4.

Severity Medium:
- **M1** Pending changes for uninstalled packages are never applied/cleared (LockCoordinator Phase E ≈ l.215).
- **M2** `freezer.freeze()` result ignored; lock recorded even if the freeze failed.
- **M3** `DataStoreLockStateStore.recordUnlockFailure` does `?: return@edit` when there is no record → "history not lost" is false for packages without a record (≈ l.142).
- **M4** `UsageStatsReader` queries 48 h of events per target per tick on the Main thread; the service calls `reschedule()` every tick per over-limit target.
- **M5** `MainViewModel` recomputes `resolveProtected()` (heavy IPC) on every combine emission (every second on the lock screen) on Main; `checkStatus()` in `init` on Main.
- **M6** `UsageSnapshotStore.decodeSnapshots` uses `require` → one corrupt line crashes the UI flow (other codecs are tolerant).
- **M7** `DeviceCriticalPackageResolver`: NPE risk on `it.activityInfo.packageName`; `isEligibleTarget` excludes ALL `FLAG_SYSTEM` apps (YouTube/Chrome on Xiaomi cannot be targeted); protects all enabled IMEs / accessibility services / GMS.
- **M8** Non-atomic `setTargets` + `setPending` in Phase E (read-modify-write across stores).
- **M9** `isPackageInstalled` returns `true` on any unexpected exception (keeps stale state).

Build / config / docs:
- **B1** `gradle.properties` has a hard-coded `org.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64` and a private proxy `10.97.201.106:8080`.
- **B2** minSdk / compileSdk / targetSdk inconsistency; `check-environment.sh` only checks `android-34`.
- **B3** `proguard-rules.pro` / `AGENTS.md` missing (probably stale claim); `keystore.properties` required even for unit tests.
- **B5** `device_admin.xml` has an empty `uses-policies` and a stale "Stage 1" comment.
- **B6** Dead `SDK_INT >= TIRAMISU/S` branches (minSdk is 34).

Low / quality:
- **L1** Dead code: `onTick`, `AppFreezer.unfreeze` (doc contradicts behavior), `getAllFrozenPackages`, `ACTION_RETRY`, `ensureRecord`, `markUnlockedIfExpired`, `recordReleasePending`, unused imports (`Uri`). **Delete only after `grep -rn` proves zero usages (main AND test).**
- **L2** Duplicated `isInsideWindow`; 4× duplicated unfreeze/failure block; nullable `lockCoordinator` that is never null; stale "Stage N / §N" comments; `catch (_: Exception) {}` swallowing in `MainActivity`.
- **L3** Hard-coded English strings in `MainViewModel` feedback (Persian resources exist).
- **L4** Text codecs instead of JSON — **DO NOT CHANGE** (rule R4).
- **D1** README test count wrong; README "Android 15 only / public use" contradiction; README vs FINAL_README duplication.
- **T1** No tests for service, scheduler, `DeviceDpmGateway`, receivers, `MainActivity` intent path.

Positives to PRESERVE: clean layering, DPM behind an interface with fakes, mutex-serialized coordinator, monotonic lock-until, no INTERNET permission, `allowBackup=false`, `AlarmReceiver` not exported.

---------------------------------------------------------------------
## 5. PHASES

### P0 — Triage (READ-ONLY; no code changes; ~15 min)
Goal: turn the §4 claims into verified facts and settle the open deadline question.

Steps:
1. `git status`, `git log --oneline | head`, record the current commit hash in the report.
2. For every ID in §4 (H1–H7, M1–M9, B1–B6, L1–L3, T1): open the cited code, decide `CONFIRMED / ALREADY FIXED / FALSE`, and give `file:line` evidence. Write the result to `reports/triage.md` as ONE compact table: `ID | status | evidence | planned phase`. For H7, add 3–5 lines describing options only (e.g. device-owner time policies), implement nothing.
3. **Deadline question (important).** On the phone run (read-only): `adb shell date`, `adb shell "date +%s"`, `adb shell dumpsys alarm | grep -E "com.limiter.app"`. A stored lock for `com.dv.adm` has deadline `1792244640000` ms = **2026-10-17 17:14 (+03:30)**, while its rule is hide 17:10 / reveal 17:14. Compare the phone's epoch seconds with `1792244640`:
   - phone epoch **<** 1792244640 → the lock was created ~5 days long instead of ~4 minutes → **deadline-computation bug**. 
   - phone epoch **≥** 1792244640 → the lock is expired but was not reconciled.
   State which case it is. Do NOT launch the app or fix anything on the phone.
4. Find the function that computes the lock deadline for a hide/reveal window (start with `TimeMath.kt` and `LockCoordinator.kt`). Write a table of **expected vs actual** by reading the code and, where possible, by writing a **scratch unit test that is allowed to FAIL** (commit it only if it fails for a real bug; name it `…_reproducesDeadlineBug`). Cases (timezone `Asia/Tehran`):
   a. hide 17:10, reveal 17:14, now 17:10:30 → expected lockUntil = today 17:14.
   b. same, now 17:13:59 → today 17:14.
   c. same, now 17:14:00 → not locked; next hide = tomorrow 17:10.
   d. overnight window hide 21:30, reveal 07:00: now 22:00 → tomorrow 07:00; now 03:00 → today 07:00.
   e. window start exactly at `now` (17:10:00).
   f. timezone change between schedule and fire.
   Rule: any computed `lockUntil - now > 24 h` for a window rule is a bug.
5. Also check and report: why an `ACTION_HIDE` broadcast for `com.dv.adm` was dispatched at 17:17:57 (after its 17:14 reveal) — look at `dumpsys alarm` stats/logs only; no speculation.
6. `STATE.md` update, report, STOP.

Done when: `reports/triage.md` exists and covers every ID, and the deadline case is named with evidence.

### P1 — Security & robustness of entry points (H1, H5, H6)
Steps:
1. **H1.** Remove the intent-extras handling (`add_target`, `limit`, `hide`, `reveal`) from `MainActivity` in the main source set. If it is used for testing, move it to `app/src/debug/` guarded by `BuildConfig.DEBUG` AND still routed through the same self-lock/limit rules as the UI (no bypass). Confirm in `AndroidManifest.xml` that `MainActivity` is exported only because of the launcher intent filter and that no other component is exported by accident (list every `exported=` value in the report). Add a JVM test if the logic is extractable; otherwise state `NOT TESTABLE (JVM)` and give me the on-device check: `adb shell am start -n com.limiter.app/.MainActivity --es add_target com.example.fake --ei limit 1` must change nothing.
2. **H5.** In `AlarmReceiver` and `SystemEventsReceiver`: call `goAsync()` once; launch work in an application-level scope with `SupervisorJob`; **exactly-once** `pendingResult.finish()` in `finally` (also on exception and cancellation); add a hard timeout (e.g. `withTimeout(8_000)`) so the receiver can never hang. Keep the existing `processAlarm` logic; only wrap it. Tests: finish called once on success, on exception, on timeout.
3. **H6.** Wrap every `startForegroundService` call (`LimiterApp.onCreate`, `MainActivity.onStart`, receivers) in `try/catch` for `ForegroundServiceStartNotAllowedException` and generic `Exception` (log with tag `Limiter`, never crash, never swallow silently). In `UsageMonitorService.monitorLoop` wrap each iteration in `try/catch`; rethrow `CancellationException`; on other exceptions log, `delay` a bounded backoff, continue. The loop must never die silently. Add a JVM test for the loop body if it is extractable.
4. Full tests + `assembleDebug`. Report. STOP. (No phone changes in this phase.)

### P2 — Time windows and alarms (deadline bug, H4, exact alarm)
Steps:
1. **Deadline bug.** Use the P0 findings. If a bug exists: first make the reproducing test fail, then fix the computation in the pure function (`TimeMath` or wherever it lives) so that all cases a–f from P0 pass. Keep `lockUntil` monotonic (never move an active lock earlier than a stricter existing lock). If P0 concluded "expired but not reconciled", skip the fix and write a regression test for that scenario instead.
2. **H4.** In `TimeWindowScheduler`: (a) schedule an alarm for `ReleasePending` records; (b) ensure the retry alarm (`ACTION_RETRY`, request code stable) is actually scheduled whenever any `nextUnlockRetryAtMillis` exists; (c) make hide/unlock alarm request codes **deterministic** from `(packageName, action)` (e.g. stable hash), not map order; (d) **cancel** alarms of targets that were removed or whose window changed. Keep exact→inexact fallback and the `ScheduledExact / ScheduledInexact / Failed` result reporting. Tests: removed target cancels its alarms; changed window replaces alarm; two targets never share a request code.
3. **Exact alarm UI.** In the status card, add a button that opens `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` (with the app's package URI) when `canScheduleExactAlarms()` is false; re-check on resume.
4. **Phone (authorized, minimal):** run `adb shell appops set com.limiter.app SCHEDULE_EXACT_ALARM allow` then `appops get`. No install in this phase unless P2 changed code that I asked you to verify on-device — if so follow §2 fully and ask me first.
5. Full tests + assembleDebug. Report. STOP.

### P3 — Lock/unlock correctness (H3, M1, M2, M3, M8, M9, fallback)
Steps:
1. **H3 (authorized by D2).** In `UsageMonitorService` poll tick: if any lock record has `untilEpochMillis <= now` or `nextUnlockRetryAtMillis <= now`, call `lockCoordinator.reconcile()` (and `reschedule()` once, not per target). Must be cheap and idempotent (no extra writes when nothing is due). Never call freeze/unfreeze from the service. Tests with fakes: nothing due → zero reconcile calls; due → exactly one.
2. **M2.** Check the result of `freezer.freeze()`: if it fails, do **not** record the package as locked-and-verified; record a failure/retry using the same bounded-backoff mechanism used for unlock failures, and log it. Test.
3. **M3.** Make `recordUnlockFailure` create a minimal record when none exists (so failures and retry timestamps are not lost), using only existing fields/defaults (rule R4). Test.
4. **M9.** `isPackageInstalled` must NOT return `true` on unexpected exceptions; return `Unknown` (use the existing `PackageInstallStatus`). Test.
5. **Fallback against stuck locks.** For a lock record that Limiter itself created: after **3 consecutive `Unknown` results** during release, attempt `restoreVisibility` + `restoreSuspension` anyway (both are harmless on absent packages); commit `recordUnlock` **only if both return Ok**. Tests: unknown ×2 → still waiting; unknown ×3 → restore attempted; restore fails → retry state preserved.
6. **M1.** Pending changes for packages that are confirmed `NotInstalled` must be applied or cleared by the existing state machine (decide by reading Phase E; keep non-erasure for `Unknown`). **M8.** If `setTargets` + `setPending` can be made one atomic DataStore edit using the existing store API without changing formats, do it; otherwise document the residual risk in the report and leave it.
7. Full tests + assembleDebug. Report. STOP.

### P4 — Product rules: limits and self-lock (D5)
Read first: `SettingsPolicy`, the self-lock policy class and its tests (`SelfLockPolicyTest`, `R3SelfProtectionAndPolicyTest`, `TargetScheduleEditTest`, `AppPickerAndConfigTest`), `MainViewModel`, `SelfLockScreen`, the add/edit dialogs. All rules below live in **pure policy code with unit tests**, not in the UI.

**Rule A — usage limit bounds.** Constants: `MAX_DAILY_LIMIT_MINUTES = 720`, `MAX_LIMIT_INCREASE_MINUTES = 30`, minimum 1.
- New target: limit in `[1, 720]`, applied immediately (adding a restriction is tightening).
- Decrease: applied immediately.
- Increase by `d = proposed − currentActiveLimit`: if `d > 30` → **rejected** with a specific error; if `proposed > 720` → rejected; if `0 < d ≤ 30` → treated as loosening and handled by the **existing delayed-change mechanism** (pending change), unchanged.
- **Cumulative cap:** compare against the **currently active** limit, never against a pending value, so queuing several +30 pending changes cannot exceed +30 total. Replacing an existing pending change is allowed only if the new value is still within +30 of the active limit.
- **Existing data:** `ir.ilmili.telegraph` currently has limit 1000 min (> 720). Treat the effective limit as `min(stored, 720)` everywhere (reader/policy), log it, and apply the clamp as a tightening update on next reconcile. Do not delete the target. Tell me in the report that this happened. Existing pending change `com.supercell.clashroyale` 100→120 (+20) must remain valid.
- Window rules (hide/reveal): keep today's semantics and delays unchanged.

**Rule B — self-lock blocks only LOOSENING.** While self-lock is active:
- ALLOWED: viewing the dashboard, the full installed-app list (picker), usage, pending changes; **adding NEW target apps** (not already targeted); decreasing a limit; cancelling a pending loosening change.
- BLOCKED: removing a target, increasing a limit, widening/shortening a window in the loosening direction, anything classified as loosening by `SettingsPolicy`.
- Enforce in **one** place used by both `MainViewModel` and `LockCoordinator.tryEditConfig / tryRemoveConfig` (fixes H2): a pure `SelfLockGate`-style function taking `TimeSource.now()`. Remove the use of `System.currentTimeMillis()` in `SelfLockScreen`; self-lock status must refresh on a ticker driven by `TimeSource` (also on the dashboard), and re-evaluate exactly at window start/end.
- Picker during self-lock: apps already targeted are shown disabled with the label "already limited"; they cannot be re-added (that would be an edit/bypass).
- Because adding during self-lock cannot be undone until the window ends, show a confirmation dialog before adding: "این کار تا پایان قفل خودکار قابل برگشت نیست" (+ English).
- Strings for all new errors/messages in `strings.xml` (fa + en); no hard-coded English in the ViewModel.

Tests (minimum): limit new=720 ok / 721 rejected / 1 ok / 0 rejected; increase +30 pending / +31 rejected; cumulative pending cannot exceed +30; decrease immediate; clamp 1000→720; self-lock allows add-new and decrease, blocks remove/increase/loosen; coordinator-level calls obey the same gate; `TimeSource` boundary (window starts/ends at an exact millisecond). Update existing tests only where the requirement changed, and list them in the report.
Full tests + assembleDebug. Report. STOP.

### P5 — Performance and tolerance (M4, M5, M6, M7)
1. **M4.** Run `UsageStatsReader` queries off the Main thread (`withContext(Dispatchers.Default)` or IO). Narrow the query window to what the calculation actually needs (document the reasoning; keep results identical — existing `UsageCalculatorTest` must stay green). Call `reschedule()` at most once per tick and only when the plan changed.
2. **M5.** Compute `resolveProtected()` once, cache it (invalidate on package-change broadcast or app resume), never on Main, never per emission. Move `checkStatus()` out of `init`/Main.
3. **M6.** Make `UsageSnapshotStore.decodeSnapshots` tolerant: skip a corrupt line and log it, same pattern as `Codec`. Test with a corrupt line.
4. **M7.** Fix the NPE risk (null-safe access on `activityInfo`). **System-apps:** add a "Show system apps" toggle in the picker, **default OFF**, with a confirmation the first time it is switched on; system apps that are launchable and NOT in the protected set (phone, SMS, Settings, launcher, IME, emergency, Limiter itself) become selectable. Do not remove any protection. Test the resolver with fakes.
5. Full tests + assembleDebug. Report. STOP.

### P6 — UI/UX redesign (Compose Material 3)
**No logic changes in this phase.** Only presentation, strings, and small view-state helpers. Keep all `MainViewModel` public behavior and policy classes untouched.
Read `LimiterScreen.kt` (≈1200 lines) in chunks, list its composables in the report first, then work **one screen at a time** (dashboard → add/edit target → picker → self-lock screen → status/permissions), taking a phone screenshot after each (`adb exec-out screencap -p`; install only per §2) — or, if install is not possible, say `NOT VERIFIED ON DEVICE`.

Design requirements (all must be met):
- Material 3, light + dark, edge-to-edge with correct insets, 48 dp touch targets, **RTL-correct**, Persian default + English, no hard-coded user text. Numbers formatted consistently (e.g. "۲ ساعت ۳۰ دقیقه" via string resources/plurals).
- **Dashboard:** top "health" row (Device Owner · Usage Access · Notifications · Exact alarm · Self-protection) — compact chips; any failing chip is tappable and opens the exact fix (Usage Access settings, exact-alarm settings, notification settings, battery/autostart guidance). Below: one **card per target app** with real app icon + name, used/limit progress bar (turns warning color near the limit), state chip ("Locked until 17:14" / "Active" / "Pending change in 2 days"), and a next-event line. Pending-change banner with countdown. Empty state with a clear call to action. FAB "Add app".
- **Add/edit target:** full-screen searchable list with icons (picker). Limit input is **never raw minutes typing**: duration chips (15 m, 30 m, 1 h, 2 h, 4 h, 8 h, 12 h) plus a custom hour/minute picker (hours 0–12, minutes in steps of 5, clamped to the 12 h maximum). When editing an existing limit show helper text: "current 2 h — can be raised by at most 30 min per change; takes effect after the delay" (use real values). Windows use Material 3 `TimePicker` with a summary like "Hidden 22:00 → 07:00". Show validation errors inline (from P4 rules).
- **Self-lock screen:** calm explanation, live countdown to the end of the window, a visible but clearly secondary "Add app" action and a read-only view of limits. **No** "unlock now", "skip", "snooze" or any control that looks like it can end a lock early.
- Locked-app/status messages are neutral, never scolding.
- Consistent spacing scale (4/8/12/16/24 dp), one type scale, no more than one accent color plus semantic colors (ok/warn/error). Accessibility: contentDescription on icons, no color-only meaning.
- Performance: no heavy work in composition; icons loaded off-thread and cached; `LazyColumn` with stable keys.
Report: one line per screen + screenshots paths. STOP.

### P7 — Cleanup, config, docs (B1, B2, B3, B5, B6, L1, L2, D1, T1)
1. **B2/D1-decision:** set `compileSdk = targetSdk = minSdk = 34` in `app/build.gradle.kts` (if not already); make `scripts/check-environment.sh` consistent; fix the AGENTS.md B3 wording accordingly.
2. **B1:** if `gradle.properties` still has the hard-coded `org.gradle.java.home` or the private proxy, remove them from the **committed** file (move machine-specific values to `~/.gradle/gradle.properties` or rely on `JAVA_HOME`), and prove `./gradlew assembleDebug --offline` and the tests still work with `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` exported. If proof fails, revert.
3. **B3/B5/B6:** make unit tests runnable without `keystore.properties` if that is a trivial, safe change (else document). Fix the stale `device_admin.xml` comment. Remove dead `SDK_INT` branches now guaranteed by minSdk 34.
4. **L1/L2:** delete dead code **only** after `grep -rn` shows zero usages in main and test; remove unused imports; replace `catch (_: Exception) {}` with logged handling; dedupe `isInsideWindow` and the repeated unfreeze/failure block **only if** the existing tests still pass unchanged. Leave L4 alone.
5. **T1:** add the missing tests that can run on the JVM with fakes (scheduler request codes/cancel logic if not already done in P2, service tick decision logic, receiver completion). State clearly what remains untestable on the JVM.
6. **D1:** fix README (real test count taken from the last full run; remove "public use" contradiction; one source of truth between README and FINAL_README — keep both files but make one point to the other). Document the escape path (`adb shell dpm remove-active-admin com.limiter.app/.admin.LimiterAdminReceiver`) and the self-lock rules (+30 min / 12 h).
7. Full tests + assembleDebug. Report. STOP.

---------------------------------------------------------------------
## 6. FINAL ON-DEVICE CHECKLIST (for ME, the human — do not run it yourself)
After P7 I will: (1) install the debug build, (2) confirm Device Owner and services via `dpm list-owners` / `dumpsys activity services`, (3) test a 1-minute window on `com.dv.adm`: lock → deadline correct → automatic release without opening the app, (4) test self-lock: can view and add apps, cannot loosen, (5) try an intent injection (`am start … --es add_target`) and confirm nothing changes, (6) reboot and confirm locks recover, (7) try to uninstall / force-stop / clear Limiter from Settings and confirm it is blocked.

---------------------------------------------------------------------
## 7. TEMPLATES

`reports/reportfor_<phase>.md` (≤ 60 lines):
```
# Report <phase> — <title>
Commit before/after: <hash> → <hash>
## Done (ID → what, file:line)
## Verified (COMPILED | TESTED (JVM) | VERIFIED ON DEVICE | NOT RUN) + new test names + counts (total tests before → after)
## Not done / blocked / needs my decision
## Changed files (git diff --stat, one-line reason each)
## Risks / things I should test on the phone
```

`STATE.md` (≤ 40 lines): current phase and commit; decisions D1–D5 (one line each); list of phases done; OPEN bugs (ID + one line); phone facts (Device Owner ok, FGS ok, exact alarm status, which targets are locked and until when); invariants reminder (R4, R10).
