# Limiter — Canonical Agent Instructions (Merged)

> **Canonical filename:** keep this document at the repository root as `AGENTS.md` (plural). Do not create a competing root `AGENT.md`/`agent.md` with different instructions. This file combines the technical specification, the R0–R4 execution workflow, and the supplied historical notes.

## A. Mission and binding precedence

Build **Limiter**, a personal-use, offline-only Kotlin Android application for one personally controlled Xiaomi Redmi 12 / MIUI test device. It enforces per-app daily usage limits and scheduled nightly lock windows through legitimate Android Device Owner / `DevicePolicyManager` APIs only.

Use this precedence when any instructions conflict:

1. Safety and recovery invariants in this file, especially Sections B–D.
2. Current canonical R0–R4 workflow in Section C.
3. Technical acceptance criteria in the legacy technical reference later in this file.
4. Current public interfaces and persisted-data contracts, unless they violate a higher rule.
5. Historical `STAGE*_NOTES.md`, recovery notes, old reports, and prior agent claims.
6. Existing implementation details.

The legacy Stage 1–4 technical sections below are retained because they contain important engineering requirements. Their original **execution order is not authoritative**. The only current workflow is R0, R1, R2, R3, R4 in Section C. Read the technical sections as implementation/acceptance requirements to be mapped to that current workflow. Do not follow a legacy stage instruction if it conflicts with the R0–R4 sequence or the explicit conflict decisions below.

Never declare work complete from a prior agent's narrative alone. Verify the actual repository, current environment, files, commands, test output, and—where relevant—the physical state on the actual phone.

## B. Hard rules

### B1. Safety, permissions, and offline operation

- Do not request, store, print, echo, or place passwords, signing secrets, or credentials in reports or source. If an operation needs `sudo` or an interactive keystore password, provide the exact command for the human to run; do not ask them to send the password.
- Do not declare `android.permission.INTERNET`.
- Do not use root, hidden APIs, accessibility abuse, overlay tricks, self-reinstallation, package-manager exploits, or persistence mechanisms that circumvent Android's administration model.
- Use only legitimate, supported `DevicePolicyManager` APIs.
- If Device Owner is absent, do not create new enforced locks, do not call privileged enforcement APIs repeatedly, and do not claim physical enforcement. Publish/show an explicit `DeviceOwnerRequired` / `NotDeviceOwner` state.
- There is no app-level `unlock`, `skip`, `snooze`, `forceUnlock`, `shortenLock`, `removeWhileLocked`, or `debugUnlock`. No UI, notification, widget, receiver, ViewModel, service, or repository may bypass lock state.
- Only `LockCoordinator` owns `AppFreezerApi`. Only `LockCoordinator.reconcile()` may perform the normal transition of an existing `LockedUntil` record to `Unlocked`.
- Persist a lock before freezing. To unlock, physically unfreeze and verify first; only then persist `Unlocked`. If unfreeze fails, retain the logical lock and schedule bounded retry.
- Do not claim that the system is “unbreakable.” State only that persisted logical state, actual Android package state, and reconciliation are designed to converge under normal supported Android Device Owner operation.

### B2. Explicit recovery boundary

Never enable or add these restrictions as part of Limiter:

- `UserManager.DISALLOW_DEBUGGING_FEATURES`
- `UserManager.DISALLOW_FACTORY_RESET`
- `UserManager.DISALLOW_SAFE_BOOT`

Do not disable ADB, factory reset, safe boot, or system recovery. Preserve the supported debug recovery path:

```bash
adb shell dpm remove-active-admin com.limiter.app/.admin.LimiterAdminReceiver
```

This path depends on the debug manifest declaring `android:testOnly="true"`. Do not remove that test-only recovery path unless the human gives explicit written approval after reviewing the recovery consequences. Do not introduce broader `DISALLOW_APPS_CONTROL` or `DISALLOW_UNINSTALL_APPS` restrictions as a substitute for targeted self-protection. In R3, use the targeted documented mechanisms from the technical specification: `setUninstallBlocked(self, true)` and `setUserControlDisabledPackages([self])` (through the project gateway), while preserving ADB and system recovery.

### B3. Target-device/API and architecture invariants

- Application ID and namespace: `com.limiter.app`.
- Device Owner component: `com.limiter.app/.admin.LimiterAdminReceiver`.
- Determine `DEVICE_API_LEVEL` on the actual phone with `adb shell getprop ro.build.version.sdk`; `minSdk` equals that returned value.
- Keep `compileSdk = 34` and `targetSdk = 34` unless the human explicitly approves a change.
- Expected baseline: JDK 17, Gradle 8.7, AGP 8.5.2, Kotlin 1.9.24, Compose BOM 2024.06.00, Compose compiler 1.5.14, Java/Kotlin target 17. Do not add a version catalog.
- Preserve existing architecture and public contracts unless current evidence proves a defect. In particular, do not casually rewrite `DpmGateway`/`DeviceDpmGateway`, `LockCoordinator`, `UsageStatsReader`, `UsageCalculator`, `UsageMonitorService`, `SettingsPolicy`, time abstractions, or persistence models.
- Verify Android APIs against local SDK stubs and the actual `DEVICE_API_LEVEL`; never guess framework API availability.
- Business logic must use the injected `TimeSource`. Do not call `System.currentTimeMillis()`, `Instant.now()`, `LocalDateTime.now()`, or `ZonedDateTime.now()` directly inside business logic.
- Times of day use `Int minuteOfDay` in `0..1439`; scheduling uses the supplied `ZoneId`.
- Exactly one DataStore instance per file; all preference delegates for a file live in one `Stores.kt`.
- The physical frozen invariant is `hidden == true AND suspended == true`. Either property alone is not a successful freeze.
- A persisted `LockedUntil` may be extended but never shortened. `lockUntilAtLeast(pkg, until)` uses `max(existingUntil, until)`.
- `eligibleFromEpochDay` on each pending change is the eligibility authority; a global rollover marker is not.
- Missing packages must not remain logically or physically locked indefinitely. Protected packages cannot receive new locks; an already active lock on a package that becomes protected expires normally, then the package must remain excluded from future locks.

### B4. Signing continuity and CI

- The local `limiter.jks` is the required signing key for installable debug, release, and future update APKs. Keep it outside version control. Keep `keystore.properties` gitignored. Never print its contents or secret values.
- Do not silently fall back to a machine-specific default debug keystore. If the required key/configuration is absent, fail clearly and report exactly what the human must do.
- Back up `limiter.jks`; losing it may prevent normal signed updates.
- The supplied Stage 1 history says a CI workflow generated a dummy keystore. This is a **historical claim that conflicts with strict signing continuity**. Do not treat a CI-generated key as the canonical signing key or distribute an APK signed by it as a valid update artifact. Inspect the actual workflow during R0 and report the conflict. Do not change signing architecture secretly; explain the options and get explicit approval before any CI-only signing exception.
- Do not ask the human to provide a keystore password in chat. Any key generation/import/password prompt must be completed by the human locally.

### B5. Logging, evidence, and scope control

- Use the project logging wrapper and tag `Limiter`; do not add noisy ad hoc logging in production code.
- Log service start/stop, each monitor poll's package/usage/limit, lock decisions, DPM operation and result, physical-state verification, and permission/Device Owner status. Never log secrets.
- Do not make a stage unescapable. No extra anti-recovery restriction or destructive policy is allowed without explicit written approval.
- Keep changes limited to the current R-stage. Do not sneak in later-stage UI, self-protection, or optional features.
- Every claim in the report must be evidence-backed. Distinguish **verified**, **not run**, **blocked**, and **inferred**. Include exact commands, exit codes, and relevant output excerpts (redacting secrets).

## C. Canonical execution workflow — R0 through R4

**Run exactly one R-stage at a time.** At stage start, read this complete `AGENTS.md`, every existing file under `reports/`, all relevant `STAGE*_NOTES.md`, `STAGE3_DEVICE_TEST.md` if present, and the relevant source/config files. Read all previous reports before coding. State the following five items in the stage report and concise handoff: (1) current stage and objective, (2) files/reports read, (3) verified environment status, (4) implementation/tests planned or performed, and (5) remaining blockers and required human/device checks.

### Environment gate — mandatory at R0 and before any build-dependent work

The historical notes disagree about the environment: Stage 1 claims JDK 17 and passing builds; Stage 2 says no tests ran because the agent context had OpenJDK 25 and missing/unverified Android SDK/JDK 17. Neither history proves the current environment. Verify current state.

Check at minimum:

```bash
java -version
printf 'JAVA_HOME=%s\n' "$JAVA_HOME"
./gradlew --version
./scripts/check-environment.sh
```

Also verify that `$ANDROID_HOME` or `$ANDROID_SDK_ROOT` points to the real SDK and that platform 34 exists, including `platforms/android-34/android.jar` and required build-tools. If `check-environment.sh` is missing, report that fact and perform only non-mutating environment checks; do not pretend the script passed. If JDK 17, Android SDK platform 34/build-tools, Gradle execution, or a required tool is missing, malformed, or fails its gate, **STOP before writing or modifying implementation code**. Print the exact commands the human must run and the exact failing evidence; do not attempt slow package downloads, toolchain bootstraps, or architecture changes to work around the blocker. After the human fixes the environment, the stage must restart its gate.

### R0 — Audit, environment, and baseline (no implementation-code changes)

1. Read the whole canonical `AGENTS.md`, every prior report, all previous-stage notes, current manifests/Gradle files, source tree, tests, `.gitignore`, signing config names (never values), CI workflow, and current `git status`/diff/history.
2. Run the environment gate above. If it fails, stop with an R0 report; do not code.
3. Confirm target device/API information only from an actual device result. Do not infer `DEVICE_API_LEVEL` from the product name.
4. Inspect the existing code to see what is really present. Do not re-create Stage 1/2 artifacts just because notes say they were created. Do not assume prior test claims are current proof.
5. Establish baseline build/test status, where environment permits:
   ```bash
   ./gradlew assembleDebug
   ./gradlew testDebugUnitTest
   ```
   Record each command and exit code separately. If a command is blocked, record the cause; do not mark it passed.
6. Review the actual GitHub Actions signing workflow against B4. Do not print credentials.
7. Shallow-clone `aistra0528/Hail` into `../refs/hail` only if network and filesystem conditions allow, treat it as read-only, inspect patterns only, do not copy code because of its GPL license, and write observations to `reports/ref_notes_hail.md`. If cloning is blocked, report it without stalling or inventing findings.
8. Write `reports/reportfor_stage0.md` from the report template in Section E. Do not alter implementation files in R0. A missing `reports/` directory may be created solely to store the required report/reference notes.
9. Stop. Do not begin R1 in the same run.

### R1 — Make locking actually work (core enforcement correctness)

Goal: reliably converge persisted lock state and actual Android package state on the test device, without adding R3 self-protection restrictions or building the final polished UI.

1. Read the R0 report and all referenced source/contracts. Use the existing architecture; patch only demonstrated defects.
2. Ensure the required core exists and behaves per the technical specification: `DpmGateway`/`DeviceDpmGateway`, freezer behavior, `LockCoordinator.reconcile()`, persisted `LockRecord`/retry state, `UsageStatsReader`, `UsageCalculator`, `SettingsPolicy`, `TimeMath`, pending-change eligibility, polling, alarm/recovery receivers, and system-event reconciliation. If a piece already exists and is correct, do not rewrite it.
3. Implement/repair the critical invariants: Device Owner absence produces no fake enforcement; `hidden && suspended` is required; partial physical states are repaired; the recovery set includes effective targets, all `LockedUntil` records, and physically frozen packages that are discoverable; missing packages clear stale lock/retry state; active lock times never shorten; normal unlock physically unfreezes and verifies before state becomes `Unlocked`; failed unfreeze persists bounded retry; pending settings are applied after unlock in the same reconciliation cycle if eligible; daily-limit lock uses `max(next reveal occurrence, next local midnight strictly after now)` to avoid midnight flapping.
4. Ensure `LockCoordinator` is the sole owner of the freezer and serializes mutations affecting settings, lock state, and physical freeze. Do not add a direct `unlock()` endpoint.
5. Ensure monitor/service logging and polling are diagnostic but are not the authority for lock state. Check the API 34 Device Owner-compatible `systemExempted` foreground service path and exact-alarm permission/fallback behavior against local SDK/API constraints.
6. Add/fix mandatory pure-unit and fake-gateway tests from the technical reference. Tests may simulate DPM via fakes only; do not claim that JVM tests prove Android enforcement.
7. Run `./gradlew assembleDebug` and `./gradlew testDebugUnitTest`; report each actual result. If the build/environment gate fails, stop code changes and report the blocker.
8. Write `reports/reportfor_stage1.md` using the template. Include a human-run phone test checklist; do not claim device-level success without actual evidence. Stop after the report. If core physical enforcement is not verified on the phone, mark the stage **implementation complete / device verification pending**, not fully validated.

R1 does **not** include self-protection policy hardening (R3), full AppPicker/time-input workflow (R2), or the R4 visual polish.

### R2 — App picker and time/configuration input

Goal: make target selection and time/limit entry usable while preserving all core policies.

1. Read all reports and notes before edits. Implement the narrow app-picker and time/configuration input path against the existing models and `LockCoordinator.tryEditConfig(...)` / `SettingsPolicy` contracts.
2. Picker must include only installed, launchable, eligible non-system/user apps that are not protected and are not already selected. Default-deny system/preinstalled packages. Use the narrowest package-visibility declarations that actually work; do not add `QUERY_ALL_PACKAGES` without verified necessity.
3. Validate limits and times exactly: daily limit `1..1440`, minute-of-day `0..1439`, hide and reveal cannot be identical. Use a duration/minute control and time picker appropriate to the existing UI. Handle cross-midnight ranges correctly. Use default config `60 minutes`, hide `23:00`, reveal `07:00` only when adding a new target, as specified in the technical reference.
4. Every add/edit/remove goes through the coordinator and the policy layer; no direct repository write from UI. Never expose a bypass while locked. Loosening/removal rules and `eligibleFromEpochDay` remain authoritative.
5. Add/fix tests for picker filtering, protected filtering, invalid config, add/edit policy, and deferred-change feedback.
6. Run `./gradlew assembleDebug` and `./gradlew testDebugUnitTest`. Write `reports/reportfor_stage2.md`, with screenshots/device verification explicitly marked pending unless actually performed. Stop after the report.

### R3 — Self-protection and safe device policy

Goal: protect Limiter against ordinary uninstall/clear-data/force-stop controls without removing legitimate recovery options or freezing critical packages.

1. Read all reports, device-policy interfaces, and the target-device package resolver. Verify every policy API against the local SDK and target API.
2. Through the existing gateway/Device Owner, apply only targeted self-protection:
   - `setUninstallBlocked(com.limiter.app, true)`;
   - `setUserControlDisabledPackages(listOf("com.limiter.app"))` or the exact equivalent already encapsulated in the project gateway.
3. Do not use `DISALLOW_APPS_CONTROL`, `DISALLOW_UNINSTALL_APPS`, `DISALLOW_SAFE_BOOT`, `DISALLOW_FACTORY_RESET`, or `DISALLOW_DEBUGGING_FEATURES` as a shortcut. Do not disable ADB, factory reset, safe boot, system recovery, or the supported debug `remove-active-admin` path.
4. Verify behavior when Device Owner is missing, policy application fails, or API support differs. Fail visibly and safely; never claim protection if the DPM call fails. Ensure Limiter itself, launcher, SystemUI, Settings, installer, permission controller, dialer/telecom, SMS, active IME/accessibility/VPN, WebView provider, GMS/GSF when present, and required device-specific system packages are protected from app locking.
5. Review persisted lock state, physical recovery of orphaned hidden/suspended packages, target protection transitions, boot/package-replaced/time/timezone events, retry scheduling, and test-only recovery instructions. Do not unlock an existing lock early merely because a package becomes protected; on expiry, unfreeze and exclude it from new locks. A protected frozen package without a lock record must be unfrozen, never newly locked.
6. Add/fix gateway and coordinator tests. Run build and JVM tests and document commands/results.
7. Write `reports/reportfor_stage3.md`. Include a detailed actual-phone checklist and preserve `STAGE3_DEVICE_TEST.md` as the human/device integration record. Device-policy claims remain pending until observed on the phone. Stop after the report.

### R4 — UI/UX completion and final review

Goal: finish a clear, resilient interface without changing lock semantics.

1. Use Material 3, light/dark themes, edge-to-edge layout where compatible, 48dp minimum interactive targets, correct RTL, and string resources for Persian and English. Do not hardcode user-facing copy throughout UI code.
2. Required areas: onboarding/status (Device Owner, Usage Access, notification permission, exact-alarm status, device-policy state); home target cards (label, used/limit, remaining, lock end); app picker/search/icons; duration/time selection; read-only locked screen showing only lock end/countdown/back; clear missing-permission/non-Device-Owner/error states.
3. Refresh system status on resume. UI is an observer/controller only; all mutations go through `LockCoordinator.tryEditConfig`. No widgets, streaks, gamification, analytics, or history dashboards unless separately approved.
4. Add/fix UI-state and ViewModel tests (locked branching, no edits when locked, picker filtering, Device Owner missing). Run build and JVM tests.
5. Perform the static review in the technical reference: reject `INTERNET`; find direct DPM freeze calls outside the gateway/freezer; find direct wall-clock calls in business logic; search for bypass API names; verify lock monotonicity, pending eligibility, backoff, missing-package recovery, and protected-package handling.
6. Verify signing setup and the final local build, where the required local keystore is available:
   ```bash
   ./gradlew assembleDebug
   ./gradlew testDebugUnitTest
   ./gradlew assembleRelease
   ```
   Report `assembleRelease` as blocked, not passed, if the required keystore is unavailable. Never leak credentials.
7. Write `reports/reportfor_stage4.md` and update `FINAL_README.md` with real, verified behavior, provisioning/recovery, signing key backup, permissions, limits, scheduling, pending edits, retry failure behavior, and known device-specific assumptions. Keep device tests clearly separated from JVM tests. Stop after the report.

### Stage completion rule

A stage is complete only when its report exists and every claim is evidence-backed. A compile/test pass is not proof of physical Device Owner enforcement. Never start the next R-stage automatically. The next stage starts only after the human reviews the report and requests it. When device integration needs the actual phone or human interaction, mark it pending rather than assuming it happened.

## D. Conflict resolutions and historical notes

### D1. Technical stage plan vs R-stage workflow

The uploaded v4 specification's original Stage 1–4 sections are retained below as technical reference. The user's newer R0–R4 process is the sole execution sequence. Map the technical requirements to R0–R4 as described in Section C. A legacy statement such as “do not implement DPM in original Stage 2” explains the historical separation of core work; it does not prevent R1 from implementing the required lock enforcement after the current environment and audit gates pass.

### D2. Self-protection conflicts

Use the targeted APIs in R3 (`setUninstallBlocked` and `setUserControlDisabledPackages` for Limiter itself). Never add the wider restrictions `DISALLOW_APPS_CONTROL`, `DISALLOW_UNINSTALL_APPS`, `DISALLOW_SAFE_BOOT`, `DISALLOW_FACTORY_RESET`, or `DISALLOW_DEBUGGING_FEATURES`. Preserve supported ADB/debug recovery.

### D3. File/report naming

- The single canonical instructions file is root `AGENTS.md`.
- Active R-stage reports go in `reports/reportfor_stage0.md` through `reports/reportfor_stage4.md`.
- `STAGE1_NOTES.md`, `STAGE2_NOTES.md`, `STAGE3_DEVICE_TEST.md`, and similar original artifacts are historical/technical context. Read and preserve them; do not delete or silently rename them. If older reports conflict with current evidence, report the discrepancy.
- Do not create competing stage note files unless the human explicitly asks. Keep new work reports in the active `reports/` convention.

### D4. Historical status from the notes supplied by the human

The human supplied these historical claims. They are context, **not verified current repository state**:

- Original Stage 1 says Gradle scaffold, Device Admin receiver, debug `testOnly`, sample test, CI workflow, `limiter.jks`, `keystore.properties`, and root `AGENTS.md` were created; it claims local `assembleDebug` and `testDebugUnitTest` passed under JDK 17.
- Original Stage 1 says GitHub Actions creates a dummy keystore because `keystore.properties` is ignored. This conflicts with strict same-key signing continuity and must be audited under R0, not accepted blindly.
- Original Stage 2 says most models, persistence, policies, usage logic, services, and tests were already written by a prior agent and preserved. It also says `compileSdk`/`targetSdk` were restored to 34 and `scripts/check-environment.sh` was added.
- Original Stage 2 says **no JVM tests were run** in that session because the environment reportedly had OpenJDK 25, JDK 17 installation failed due slow `apt-get` downloads, and SDKMAN Temurin installation failed; Android SDK validation was not completed. Therefore Stage 2 test success is not established by that note.
- Recovery notes say JDK 17 and Android SDK/build-tools 34 should persist outside the repository, SDK location expected at `$HOME/Android/Sdk`, and `./scripts/check-environment.sh` is the intended verification command.
- Original Stage 3 had not started according to the supplied Stage 2 notes.

Because Stage 1 and Stage 2 describe different environment outcomes, rerun the gate and tests in the current session. Do not choose whichever historical claim seems more convenient.

## E. Required report template

Every R-stage report must use this structure, replacing placeholders with actual results:

```markdown
# Report for R<id> — <stage title>

## 1. Objective and status
- Objective:
- Status: complete / implementation complete but device verification pending / blocked / partial

## 2. Five-line handoff
1. Current stage and objective:
2. Files/reports read:
3. Verified environment status:
4. Implementation/tests performed:
5. Blockers and human/device checks:

## 3. Repository baseline
- Git branch/status and relevant existing changes:
- Existing implementation inspected:
- Prior-note claims verified or contradicted:

## 4. Files changed
- <path> — <reason>

## 5. Commands and evidence
For each command, list exact command, exit code, and useful output summary.
- `...` — exit code: ... — result: ...

## 6. Tests
- Passed:
- Failed:
- Not run / blocked, with reason:
- Explicitly distinguish JVM/unit tests from phone/device integration tests.

## 7. Acceptance criteria
For each current-stage criterion: pass / fail / not verified, with evidence.

## 8. Safety, recovery, and unresolved risks
- ...

## 9. Required human/device checks
- ...

## 10. Next permitted step
- State the next R-stage, but do not begin it in this run.
```

Never write “all tests pass,” “build works,” “Device Owner confirmed,” “freeze verified,” or “stage complete” without the corresponding current evidence. Redact paths/values only if they reveal secrets; do not put key passwords or keystore properties in reports.

## F. Original technical specification (retained)

The following technical sections are retained from the user's uploaded `AGENTS.md` v4. The canonical workflow above controls their execution order; functional invariants, implementation contracts, and tests below remain binding unless explicitly superseded in Sections B–D.

---

# Limiter — Merged Agent Specification (Canonical)

## 0. Mission

Build **Limiter**, a personal-use, offline-only Android application written in Kotlin.

Limiter enforces per-app daily usage limits and scheduled nightly lock windows.

The application is intended for **one personally controlled Android device** and runs as a **Device Owner / Device Policy Controller (DPC)**.

The Device Owner is the authoritative source of device-management authority.

Use legitimate `DevicePolicyManager` APIs only.

Do NOT use:

* root
* hidden APIs
* accessibility abuse
* overlay tricks
* self-reinstallation
* package-manager exploits
* custom persistence against Android's administration model
* exploit-based anti-uninstall mechanisms

Do not declare the `INTERNET` permission.

The main objective is:

```text
correct enforcement
+
safe recovery
+
predictable failure handling
+
testability
```

Do not optimize for unnecessary portability across every Android device.

This build targets one known test phone.

---

# 1. Stage 0 — Human Prerequisites

Before Stage 1, the human must prepare the test device.

Run:

```bash
adb shell getprop ro.build.version.sdk
```

Set the returned value as:

```text
DEVICE_API_LEVEL
```

`minSdk` must equal `DEVICE_API_LEVEL`.

Do not support Android versions older than the actual test device unless explicitly requested later.

`compileSdk` and `targetSdk` remain 34 for this project unless the human explicitly changes them.

The device must be:

* factory-reset for initial Device Owner provisioning
* primary user only
* free of user accounts during Device Owner provisioning
* free of secondary users/profiles during initial provisioning

Official Android/AOSP testing guidance requires a factory-reset device with no user accounts when setting up a Device Owner for testing.

Provision:

```bash
adb shell dpm set-device-owner com.limiter.app/.admin.LimiterAdminReceiver
```

Do not assume Device Owner provisioning succeeds on a device containing accounts.

For debug builds, `android:testOnly="true"` must be present so Android's supported:

```bash
adb shell dpm remove-active-admin com.limiter.app/.admin.LimiterAdminReceiver
```

recovery/test path is available. Android's current adb documentation specifies that `remove-active-admin` requires `android:testOnly` and removes device/profile ownership.

---

# 2. Local Signing Key

Because this application is a Device Owner and self-uninstall is blocked, **signing continuity is mandatory**.

The human must create one local keystore before installation:

```bash
keytool -genkeypair \
  -v \
  -keystore limiter.jks \
  -alias limiter \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

Keep the keystore outside git.

Create a local:

```text
keystore.properties
```

and gitignore it.

The same keystore must sign:

```text
debug APK
release APK
future update APKs
```

The build must NOT silently fall back to a machine-specific debug keystore for this project.

If the keystore is missing, fail with a clear error.

The README must explicitly say:

```text
BACK UP limiter.jks.
Losing this key may prevent normal signed APK updates.
```

No CI release signing is required.

---

# 3. Source-of-Truth Order

When requirements conflict, use:

```text
1. Safety invariants in AGENTS.md
2. Current persisted-data / public-interface contracts
3. Current-stage specification
4. Previous stage notes
5. Implementation details
```

Never use existing buggy implementation as authority over the specification.

When behavior is unspecified:

```text
choose the fail-safe behavior
record the decision in stage notes
```

Do not invent a user bypass to solve ambiguity.

---

# 4. AGENTS.md — Global Rules

Create this file in Stage 1 and keep it at project root.

```text
# Limiter — AGENTS.md

## Project

Offline-only personal Android Device Owner app.

Application ID:
com.limiter.app

Namespace:
com.limiter.app

Admin receiver:
com.limiter.app.admin.LimiterAdminReceiver

Device Owner component:
com.limiter.app/.admin.LimiterAdminReceiver

No INTERNET permission.

Target one known Android device.

## Architecture

UI
  ->
ViewModels
  ->
pure policy/state logic
  ->
repositories
  ->
thin Android adapters
  ->
Android framework

Business logic should be pure Kotlin whenever practical.

No dependency injection framework.

LimiterApp is a lightweight service locator with lazy singletons
and application-scoped coroutine scope.

No blocking or heavy initialization in Application.onCreate().

## Time

interface TimeSource {
    fun nowMillis(): Long
    fun zone(): ZoneId
}

Production:
SystemTimeSource

Tests:
FakeTimeSource

Business logic must never call:
System.currentTimeMillis()
Instant.now()
LocalDateTime.now()
ZonedDateTime.now()

directly.

Times of day are Int minuteOfDay in [0, 1439].

All scheduling semantics use the supplied ZoneId.

## DataStore

Exactly one DataStore instance per file.

All preference delegates for a file live in one Stores.kt.

No duplicate DataStore delegates.

## Android API discipline

Never guess framework APIs.

Inspect the local Android SDK stubs before using APIs.

Verify API availability against the actual DEVICE_API_LEVEL.

Do not add unnecessary compatibility branches for Android versions
below DEVICE_API_LEVEL.

## No bypass

There is no application-level:
unlock
skip
snooze
forceUnlock
shortenLock
removeWhileLocked
debugUnlock

No notification, widget, UI, broadcast, repository or ViewModel may bypass lock state.

Only LockCoordinator owns AppFreezerApi.

Only LockCoordinator.reconcile() may transition an existing
LockedUntil state to Unlocked.

## Device Owner

All DevicePolicyManager enforcement requires Device Owner.

If Device Owner is absent:

- do not create new enforced locks
- do not claim enforcement exists
- return NotDeviceOwner status
- do not repeatedly call privileged DPM APIs
- UI must clearly show Device Owner is required

Do not create a fake "locked" state solely because DPM is unavailable.

## Freeze invariant

A package is physically frozen only if:

hidden == true
AND
suspended == true

Hidden-only is NOT frozen.
Suspended-only is NOT frozen.

Partial physical states must be repairable by reconciliation.

## Lock invariant

A persisted lock may be extended but never shortened.

lockUntilAtLeast(pkg, newUntil):
max(existingUntil, newUntil)

Configuration changes never shorten an active lock.

## Protected packages

Protected packages cannot receive new locks.

Protected status is dynamically resolved on the actual device.

At minimum protect:

- Limiter itself
- Android framework package
- SystemUI
- active launcher / default HOME
- default dialer / telecom/phone infrastructure
- default SMS handler
- Settings
- package installer
- package verifier where applicable
- permission controller
- active IME
- active accessibility service packages
- active VPN package(s)
- current WebView provider
- GMS / GSF when present
- other required device-management/system-critical packages discovered
  on the actual test device

Target selection uses default-deny for system/preinstalled packages.

Only non-system user apps are eligible by default.

A package becoming protected after it was already locked is NOT
automatically unlocked early.

Its existing lock expires normally.

After expiry, it is excluded from new locking while protected.

## Package absence

If a package no longer exists on the device:

- it cannot remain physically frozen
- its lock record is considered physically resolved
- clear the stale lock/retry metadata
- keep or remove configuration according to the explicit target
  policy; do not recreate a lock for a missing package

If the package is later installed again, it starts from its persisted
configuration but with no stale lock.

## Reconciliation

reconcile() must be idempotent.

It reconciles:

Desired state
+
Persisted state
+
Actual DevicePolicyManager state

The set of packages examined by reconcile must include:

effective target packages
+
all persisted LockedUntil records
+
all physically frozen packages discoverable on-device

Never iterate only effective targets.

This is required to recover orphaned locks and prevent permanent
hidden/suspended packages.

## Lock lifecycle

Normal lock:

persist lock
then enforce physical freeze

Normal unlock:

attempt physical unfreeze
then persist Unlocked

If physical unfreeze fails, remain LockedUntil and retry.

If the package is missing, consider physical unfreeze successful.

## Retry

Unlock failures use persisted exponential backoff.

Required sequence:

30 seconds
1 minute
2 minutes
5 minutes
10 minutes
15 minutes
30 minutes maximum retry interval

Reset failure count after successful unfreeze.

No infinite tight alarm loop.

## Settings pending model

Pending changes are per-package.

Each pending change contains:

packageName
desired configuration
eligibleFromEpochDay

When a loosening edit is created:

eligibleFromEpochDay = localEpochDay(today) + 1

Never rely on one global rollover marker to determine whether an
individual change is eligible.

A pending change is applied only when:

todayEpochDay >= eligibleFromEpochDay
AND
package is unlocked
AND
package is not protected

A locked package keeps its pending change.

When a package becomes unlocked, eligible pending changes must be
processed immediately during reconciliation.

A midnight alarm exists only as a prompt to reconcile; the actual
eligibility decision comes from eligibleFromEpochDay.

## Daily-limit lock

Daily usage is:

local midnight -> now

When a daily limit is reached:

lockUntil =
max(
    nextRevealOccurrence,
    nextLocalMidnightStrictlyAfterNow
)

This prevents a limit reached shortly after midnight from unlocking
at the morning reveal and immediately re-locking for another day.

It also preserves scheduled nightly locking when the daily limit is
reached late in the day.

Daily-limit lock and scheduled-window lock are separate reasons,
but share the same LockedUntil state.

## Usage calculation

Authoritative usage comparisons use milliseconds.

Do not make enforcement decisions from rounded minutes.

Use:

usedMillis >= limitMinutes * 60_000L

Callbacks use:

onLimitReached(pkg, usedMillis)

not usedMinutes.

## Usage events

Recognize at minimum:

ACTIVITY_RESUMED
ACTIVITY_PAUSED
ACTIVITY_STOPPED
DEVICE_SHUTDOWN

ACTIVITY_RESUMED adds an active activity.

ACTIVITY_PAUSED removes that activity if active.

ACTIVITY_STOPPED also removes that activity.

DEVICE_SHUTDOWN clears all active activity state.

An orphan PAUSED or STOPPED event does NOT imply the activity was
active at the beginning of the window.

It is ignored when there is no active state for that activity.

The UsageStatsReader must query a lookback interval before local
midnight to detect activities that were resumed before the accounting
window and remained active across midnight.

Use at least the previous local day's beginning plus an additional
safe lookback suitable for the target device.

The exact lookback must be documented and tested.

## Polling

Poll planning must not delay beyond the remaining time.

nextDelay =
min(plannedDelay, remainingMillis)

If remainingMillis <= 0, return immediate/zero delay or trigger
limit processing directly according to the service architecture.

## FGS

The monitor foreground service must use the foreground-service type
allowed for Device Owner operation on the target Android API.

For API 34 targeting, use systemExempted where applicable.

Do not invent a specialUse subtype solely to avoid choosing the
correct Device Owner-supported type.

## Exact alarms

Use SCHEDULE_EXACT_ALARM only if exact scheduling is required.

Always check canScheduleExactAlarms() before relying on exact alarm
behavior.

If unavailable, use an inexact alarm fallback plus reconciliation.

Alarm availability is not the correctness authority.

## Recovery

Debug build:
android:testOnly=true

Supported testing recovery may use adb package-state commands after
verifying syntax with:

adb shell pm help

The project documentation must explain how to recover a package that
was accidentally left hidden/suspended.

There is no hidden in-app recovery path.

```

---

# 5. Project Versions

Use:

```text
JDK 17
Gradle 8.7
AGP 8.5.2
Kotlin 1.9.24
compileSdk 34
targetSdk 34
minSdk = DEVICE_API_LEVEL
Compose BOM 2024.06.00
Compose compiler 1.5.14
```

Do not add a version catalog.

---

# 6. Technical reference A — Scaffold + Device Admin + Build Infrastructure (original Stage 1)

Read no previous stage because this is the first implementation stage.

## Create

```text
settings.gradle.kts
build.gradle.kts
gradle.properties
.gitignore
app/build.gradle.kts
app/src/main/...
app/src/debug/AndroidManifest.xml
AGENTS.md
STAGE1_NOTES.md
```

## Gradle

Enable:

```text
Compose
Java 17
Kotlin JVM target 17
```

Dependencies:

```text
core-ktx
activity-compose
Compose BOM
ui
material3
ui-tooling-preview
debugImplementation ui-tooling
JUnit
```

Do not add DataStore, Lifecycle, or Coroutines yet.

## Device Admin

Create:

```text
admin/LimiterAdminReceiver.kt
res/xml/device_admin.xml
```

Use:

```xml
<uses-policies />
```

with no destructive device-admin policy declarations.

## Main Manifest

Register:

```text
MainActivity
LimiterAdminReceiver
```

Receiver:

```xml
<receiver
    android:name=".admin.LimiterAdminReceiver"
    android:exported="true"
    android:permission="android.permission.BIND_DEVICE_ADMIN">

    <meta-data
        android:name="android.app.device_admin"
        android:resource="@xml/device_admin" />

    <intent-filter>
        <action android:name="android.app.action.DEVICE_ADMIN_ENABLED" />
    </intent-filter>
</receiver>
```

## Debug Manifest

Add:

```text
android:testOnly="true"
```

to the debug application manifest only.

Release must NOT be testOnly.

## MainActivity

Placeholder:

```text
Limiter
```

## Signing

Configure local `keystore.properties`.

Debug and release use the same local keystore.

Do not silently use the default machine-specific debug key.

## Tests

One trivial JVM test.

Run:

```bash
./gradlew assembleDebug testDebugUnitTest
```

## Notes

Create:

```text
STAGE1_NOTES.md
```

with the standard sections.

---

# 7. Technical reference B — Pure Core + DataStore + Usage (original Stage 2)

Read:

```text
AGENTS.md
STAGE1_NOTES.md
app/build.gradle.kts
AndroidManifest.xml
```

Historical sequencing note only: the original Stage 2 deferred DevicePolicyManager enforcement to its original Stage 3. The current canonical execution order is R0–R4 below. Implement enforcement in R1 as defined there, while preserving all technical invariants below. Do not interpret original Stage 2/3 labels as the current workflow.

Do not implement real lock enforcement during the legacy Stage 2 workstream; this sentence is historical context only and does not override the R1 definition above.

Add:

```text
DataStore
Coroutines
kotlinx-coroutines-test
Lifecycle only if actually required by the service architecture
```

---

# 8. Stage 2 — Data Model

Create:

```text
model/TargetAppConfig.kt
model/LockState.kt
```

```kotlin
data class TargetAppConfig(
    val packageName: String,
    val dailyLimitMinutes: Int,
    val hideAtMinuteOfDay: Int,
    val revealAtMinuteOfDay: Int
)
```

Validation:

```text
dailyLimitMinutes in 1..1440
hideAt in 0..1439
revealAt in 0..1439
hideAt != revealAt
```

Lock state:

```kotlin
sealed interface LockState {
    data object Unlocked : LockState
    data class LockedUntil(
        val untilEpochMillis: Long
    ) : LockState
}
```

---

# 9. Stage 2 — TimeMath

Implement:

```kotlin
nextOccurrence(
    nowMillis: Long,
    zone: ZoneId,
    minuteOfDay: Int
): Long
```

Semantics:

```text
strictly after now
```

Test:

```text
exact boundary
normal day
midnight
DST gap
DST overlap
cross-midnight window
```

Do not use vague DST behavior.

Use `java.time`.

---

# 10. Stage 2 — ProtectedPackageResolver

Create:

```text
data/CriticalPackages.kt
data/CriticalPackageResolver.kt
```

The project is device-specific.

Do not construct a universal OEM package table.

Resolver must use the actual target device to determine:

* current launcher
* default dialer
* default SMS
* Settings
* current IME
* enabled accessibility services
* VPN package(s)
* WebView provider
* package installer
* permission controller
* SystemUI
* Android core package
* telecom/phone packages
* GMS / GSF if present
* Limiter itself

The picker should default-deny packages marked as system/preinstalled.

Only explicitly eligible user-installed applications are selectable.

Every dynamically resolved protected package must be tested through a fake resolver.

---

# 11. Stage 2 — Settings Model

Create:

```text
data/SettingsRepository.kt
data/Stores.kt
policy/SettingsPolicy.kt
```

Pending model:

```kotlin
data class PendingChange(
    val packageName: String,
    val desired: TargetAppConfig?,
    val eligibleFromEpochDay: Long
)
```

`desired == null` means removal.

---

# 12. Settings Rules

## Add

Immediate when:

```text
valid
not duplicate
not protected
```

## Edit

Lower daily limit:

```text
apply immediately
```

Higher daily limit:

```text
defer
eligibleFromEpochDay = today + 1
```

Hide/reveal changes:

Treat as a complete desired configuration and defer by default.

Do not accidentally apply a later loosening at the current instant.

If an edit contains a tightening plus loosening component, apply the
tightening immediately and preserve the loosening as part of the
pending desired configuration.

## Remove

Removal is deferred:

```text
eligibleFromEpochDay = today + 1
```

unless a future explicit requirement changes this rule.

A locked package cannot be removed immediately.

## Pending conflict resolution

For one package:

```text
latest pending desired configuration wins
```

A new pending edit replaces the previous pending edit for that package.

## Apply Pending

Apply when:

```text
today >= eligibleFromEpochDay
AND
package exists
AND
package unlocked
AND
package not protected
```

If the package is locked:

```text
remain pending
```

If the package becomes unlocked during reconciliation:

```text
apply pending in the same reconciliation cycle
```

Do not use `lastRolloverEpochDay` as the authority for eligibility.

---

# 13. Stage 2 — UsageCalculator

Pure Kotlin.

API:

```kotlin
foregroundMillis(
    events: List<UsageEventRecord>,
    packageName: String,
    fromMillis: Long,
    toMillis: Long
): Long
```

Maintain a set of active activities.

Rules:

```text
RESUMED:
    add activity

PAUSED:
    remove activity if present

STOPPED:
    remove activity if present

DEVICE_SHUTDOWN:
    clear all active activities
```

Foreground time exists while:

```text
activeActivities.isNotEmpty()
```

Clamp all intervals to:

```text
[fromMillis, toMillis]
```

Ignore unrelated packages.

Ignore events outside the query window.

### Boundary handling

The reader must supply a lookback period before `fromMillis`.

The pure calculator itself must not assume that the first event
inside `fromMillis` is a true initial state.

An orphan PAUSED/STOPPED event means:

```text
the activity is not known to be active
```

not:

```text
the activity was active from fromMillis
```

Test:

```text
multiple activities
overlapping activities
resume -> pause
resume -> stop
resume before window + pause inside window
activity still active at toMillis
orphan pause
orphan stop
device shutdown
other packages
out-of-window events
```

---

# 14. Stage 2 — UsageStatsReader

Use:

```text
UsageStatsManager.queryEvents()
```

Do not use:

```text
queryUsageStats(INTERVAL_DAILY)
```

Query:

```text
local-midnight minus lookback
    ->
now
```

and calculate accounting:

```text
local-midnight
    ->
now
```

The reader must map:

```text
ACTIVITY_RESUMED
ACTIVITY_PAUSED
ACTIVITY_STOPPED
DEVICE_SHUTDOWN
```

to the internal event model.

The lookback value must be explicit and documented.

Do not rely on query results beginning exactly at local midnight.

---

# 15. Stage 2 — UsageSnapshot

Expose:

```text
UsageSnapshotStore
```

with:

```text
NoUsageAccess
Idle
Available
```

The authoritative fields must be:

```text
usedMillis
remainingMillis
```

`remainingMinutes` is derived only for display/planning.

Limit comparison:

```text
usedMillis >= limitMinutes * 60_000L
```

never compare rounded minutes.

---

# 16. Stage 2 — PollPlanner

Rules:

```text
remaining > 30 minutes:
    5 minutes

remaining <= 30:
    2 minutes

remaining <= 12:
    30 seconds
```

Then:

```text
actualDelay = min(plannedDelay, remainingMillis)
```

Never schedule a poll later than the remaining time.

---

# 17. Stage 2 — UsageMonitorService

Foreground service.

On each tick:

```text
1. verify Device Owner
2. verify Usage Access
3. read usage
4. publish snapshot
5. for every effective target:
       if usedMillis >= limit:
           callback onLimitReached(pkg, usedMillis)
6. wait according to PollPlanner
```

The service never directly freezes a package.

No lock semantics belong inside the service.

If Usage Access is missing:

```text
publish NoUsageAccess
retry after 60 seconds
```

If Device Owner is missing:

```text
publish DeviceOwnerRequired
do not fabricate lock enforcement
```

---

# 18. Stage 2 — Manifest

Declare only permissions actually required.

At minimum:

```xml
<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
```

Use `tools:ignore="ProtectedPermissions"` where required for
PACKAGE_USAGE_STATS.

Do not declare:

```text
INTERNET
```

Do not add broad package visibility without a concrete requirement.

For launcher-app discovery, use the narrowest `<queries>` declarations
that work on the target device.

Do not add `QUERY_ALL_PACKAGES` unless a verified device-specific
requirement makes it necessary.

---

# 19. Stage 2 Tests

Mandatory:

```text
TimeMath
SettingsPolicy
codec round-trip
CriticalPackageResolver
UsageCalculator
PollPlanner
```

Run:

```bash
./gradlew assembleDebug testDebugUnitTest
```

Create:

```text
STAGE2_NOTES.md
```

---

# 20. Technical reference C — Device Owner + DpmGateway + Freezer + LockCoordinator (original Stage 3)

Read:

```text
AGENTS.md
STAGE2_NOTES.md
all Stage 2 public interfaces
```

This is the critical stage.

---

# 21. DpmGateway

Create a thin abstraction:

```kotlin
interface DpmGateway {

    fun isDeviceOwner(): Boolean

    fun isApplicationHidden(pkg: String): Result<Boolean>

    fun isPackageSuspended(pkg: String): Result<Boolean>

    fun suspend(pkg: String): DpmOperationResult

    fun unsuspend(pkg: String): DpmOperationResult

    fun hide(pkg: String): DpmOperationResult

    fun unhide(pkg: String): DpmOperationResult

    fun setUninstallBlocked(
        pkg: String,
        blocked: Boolean
    ): DpmOperationResult

    fun setUserControlDisabled(
        packages: List<String>
    ): DpmOperationResult

    fun getUserControlDisabledPackages(): List<String>
}
```

The real implementation owns all direct `DevicePolicyManager`
interaction.

Unit tests fake `DpmGateway`.

Do not unit-test Android framework behavior by pretending it is real.

The gateway returns explicit failure states.

---

# 22. DpmGateway — Freeze Behavior

### Suspend

Call:

```text
setPackagesSuspended(...)
```

and inspect its returned failed-package array.

If the target package appears in the failed array:

```text
return Failed
```

Do NOT treat partial success as full success.

### Hide

Call:

```text
setApplicationHidden(...)
```

and require a successful result.

### Freeze order

Use:

```text
1. suspend
2. verify suspended
3. hide
4. verify hidden
```

Return `Ok` only if both are true.

This creates the invariant:

```text
frozen = hidden && suspended
```

A partial result is not `Ok`.

---

# 23. DpmGateway — Unfreeze

Use:

```text
1. unhide
2. verify hidden == false
3. unsuspend
4. verify suspended == false
```

Return `Ok` only when both final conditions are false.

If one operation fails:

```text
return Failed
```

Do not mark the logical lock as Unlocked.

---

# 24. Device Policy

When Device Owner is present, enforce:

```text
self uninstall blocked
self user control disabled
date/time policy appropriate for the target API
```

`setUserControlDisabledPackages` should include the Limiter package.

This is the legitimate Device Owner mechanism for preventing ordinary
user control over the app, including clear-data/force-stop operations;
the Android framework and CTS document this behavior.

Do not use:

```text
DISALLOW_FACTORY_RESET
DISALLOW_SAFE_BOOT
DISALLOW_DEBUGGING_FEATURES
```

Do not intentionally disable recovery tools.

Do not make the device deliberately harder to recover than necessary.

Device Owner itself provides the management authority.

---

# 25. Device Time Policy

Because the limiter depends on local time, enforce the required date/time
policy only on APIs supported by `DEVICE_API_LEVEL`.

Do not compile dead compatibility branches for unsupported old versions.

Document exactly what is enforced.

Changing timezone/date manually must be treated as a system event that
causes complete rescheduling and reconciliation.

---

# 26. LockRecord

The persisted lock record must contain enough information to handle
retry without an alarm loop.

Use:

```kotlin
data class LockRecord(
    val state: LockState,
    val unlockFailureCount: Int = 0,
    val nextUnlockRetryAtMillis: Long? = null
)
```

For:

```text
Unlocked
```

retry fields must be cleared.

For:

```text
LockedUntil
```

retry fields may exist.

---

# 27. LockStateStore

Expose:

```kotlin
snapshot()
states: Flow<Map<String, LockRecord>>

ensureRecord(pkg)

lockUntilAtLeast(pkg, until)

markUnlockedIfExpired(pkg, now)

recordUnlockFailure(pkg, now)

clearUnlockRetry(pkg)

forget(pkg)
```

Rules:

```text
lockUntilAtLeast:
    never shortens

markUnlockedIfExpired:
    only succeeds when now >= until

forget:
    only when package is logically unlocked
```

`markUnlockedIfExpired()` remains the only normal transition to
`Unlocked`.

If a package is physically missing:

```text
reconcile may clear its stale lock record
```

because the actual package no longer exists.

---

# 28. Settings + Lock Transaction Semantics

`LockCoordinator` owns the serialization boundary.

Every mutation affecting:

```text
settings
lock state
physical freeze
```

is serialized by one `Mutex`.

The coordinator must prevent:

```text concurrent UI edit
+
monitor limit event
+
alarm reconcile
```

from producing a shortened or contradictory lock.

---

# 29. LockCoordinator

Public API:

```kotlin
suspend fun reconcile()

suspend fun onTick()

suspend fun onLimitReached(
    pkg: String,
    usedMillis: Long
)

suspend fun tryEditConfig(
    edit: ConfigEdit
): EditResult
```

Events:

```text
LimitLocked(pkg, until)
WindowLocked(pkg, until)
Unlocked(pkg)
```

There is no `unlock()` API.

---

# 30. reconcile — Exact Algorithm

`reconcile()` MUST use the following logical sequence.

## Phase A — Snapshot

Read:

```text
nowMillis
zone
Device Owner status
configured targets
pending changes
lock records
protected package set
physical package existence
physical hidden/suspended state where available
```

If Device Owner is not active:

```text
return NotDeviceOwner
do not create new locks
do not invoke privileged freeze/unfreeze
do not pretend physical enforcement exists
```

The UI/service may show:

```text
Device Owner required
```

---

## Phase B — Build Recovery Set

Construct:

```text
recoverySet =
    effectiveTargets
    +
all LockedUntil records
    +
all physically frozen packages
```

Do NOT use only:

```text
effectiveTargets
```

---

## Phase C — Recover Missing Packages

For every package in the recovery set:

If the package is no longer installed:

```text
clear stale physical-lock retry state
clear stale LockedUntil record
keep configuration unless explicit removal policy says otherwise
```

A missing package cannot remain logically frozen forever.

---

## Phase D — Resolve Expired Locks FIRST

For every package with:

```text
LockedUntil(until)
and
now >= until
```

attempt physical unfreeze.

### Success

If:

```text
Ok
```

or the package is missing:

```text
markUnlockedIfExpired()
clear retry metadata
emit Unlocked
```

### Failure

Keep:

```text
LockedUntil
```

Calculate exponential retry:

```text
30s
1m
2m
5m
10m
15m
30m max
```

Set:

```text
nextUnlockRetryAtMillis
```

Do not repeatedly schedule the same expired `until` timestamp.

---

# 31. reconcile — Repair Active Locks

For every:

```text
LockedUntil(until)
```

where:

```text
now < until
```

inspect both physical states.

If:

```text
hidden == false
OR
suspended == false
```

repair the physical state.

Do not change logical `until`.

A lock being physically tampered with does not shorten its logical
expiration.

---

# 32. reconcile — Apply Pending Changes AFTER Unlock

After expired locks have been processed:

```text
apply eligible pending settings
```

for packages that are now:

```text
unlocked
installed
not protected
eligibleFromEpochDay <= today
```

If a package was locked at the beginning of reconcile but unlocked
during Phase D, it MUST be eligible for application in this same
reconcile cycle.

This prevents a one-day delay.

After pending changes are applied:

```text
recalculate effective targets
```

---

# 33. reconcile — New Scheduled Window Locks

For every effective target that is:

```text
Unlocked
```

and currently inside its configured lock window:

```text
persist LockedUntil
then freeze
emit WindowLocked
```

Window semantics:

```text
hideAt < revealAt:
    [hideAt, revealAt)

hideAt > revealAt:
    [hideAt, 1440)
    OR
    [0, revealAt)
```

Window crossing midnight must be tested.

---

# 34. reconcile — Daily Limit

Daily usage is:

```text
local midnight -> now
```

If:

```text
usedMillis >= dailyLimitMillis
```

then lock:

```text
until = max(
    nextRevealStrictlyAfterNow,
    nextLocalMidnightStrictlyAfterNow
)
```

Persist first.

Then freeze.

This is intentional.

Examples:

```text
limit hit at 00:30
reveal 07:00
=> lock until next local midnight

limit hit at 23:30
reveal 07:00
=> lock until 07:00 next day
```

The first case must NOT unlock at 07:00 and then create a new
24-hour lock because the daily limit is still exceeded.

---

# 35. reconcile — Actual Frozen But Logical Unlocked

If:

```text
state == Unlocked
```

but:

```text
hidden == true
OR
suspended == true
```

then:

```text
unfreeze
```

unless the package is currently required to be newly locked by the
window/limit rules computed in this same reconciliation.

Persisted logical state is authoritative.

---

# 36. Protected Transition

If a package becomes protected after it was already locked:

```text
do NOT unlock early
```

Continue the existing lock until its normal expiry.

At/after expiry:

```text
unfreeze
mark unlocked
exclude from effective targets while protected
```

If there is a physically frozen package with:

```text
no lock record
AND
protected == true
```

do NOT create a new lock.

Attempt to unfreeze it.

This is the fail-safe behavior that prevents a launcher or other system
component from remaining permanently frozen because of lost state.

---

# 37. onLimitReached

Input:

```kotlin
pkg
usedMillis
```

Rules:

```text
ignore if Device Owner absent
ignore if package not installed
ignore if protected
ignore if not effective target
ignore if already locked until a future instant
```

Otherwise:

```text
until = max(
    nextRevealStrictlyAfterNow,
    nextLocalMidnightStrictlyAfterNow
)
```

Then:

```text
1. persist lock
2. freeze
3. record limit hit
4. emit LimitLocked
5. reschedule alarms
```

If called repeatedly:

```text
lockUntilAtLeast()
```

must prevent shortening.

---

# 38. Alarm Scheduler

Create:

```text
TimeWindowScheduler
AlarmReceiver
SystemEventsReceiver
```

Schedule at most:

```text
one next-hide alarm per effective target
one earliest-unlock/retry alarm
one next-midnight alarm
```

Do not create an alarm for every possible future day.

---

# 39. Reveal / Retry Alarm

Calculate the earliest relevant time among:

```text
future LockedUntil timestamps
nextUnlockRetryAtMillis
```

For an expired lock with failed unfreeze:

```text
use nextUnlockRetryAtMillis
```

NOT:

```text
lockedUntil + 1000ms
```

This prevents a permanent past-time alarm loop.

After successful unfreeze:

```text
cancel/replace its retry state
reschedule
```

---

# 40. Midnight Alarm

Schedule one alarm for:

```text
next local midnight
```

Purpose:

```text
prompt reconciliation
process eligible pending settings
recalculate daily usage boundary
recalculate windows
```

The alarm itself never directly applies a configuration.

`eligibleFromEpochDay` remains authoritative.

---

# 41. Exact Alarm Behavior

Use:

```text
SCHEDULE_EXACT_ALARM
```

only where required.

On API 31+:

```text
canScheduleExactAlarms()
```

must be checked.

If unavailable:

```text
use setAndAllowWhileIdle()
```

and rely on:

```text
FGS polling
boot
time change
timezone change
other reconciliation triggers
```

as correctness safety nets.

Android requires explicit handling of exact-alarm permission for apps
targeting modern Android versions; fresh installs on newer versions may
not receive `SCHEDULE_EXACT_ALARM` automatically.

---

# 42. Alarm PendingIntents

Use:

```text
FLAG_IMMUTABLE
FLAG_UPDATE_CURRENT
```

Use explicit component intents.

Use distinct action/data identifiers.

Examples:

```text
limiter://hide/<encoded-pkg>
limiter://unlock
limiter://retry
limiter://midnight
```

The alarm receiver delegates to `LockCoordinator`.

The receiver never directly mutates lock state.

---

# 43. System Events

Handle:

```text
BOOT_COMPLETED
MY_PACKAGE_REPLACED
TIME_SET
TIMEZONE_CHANGED
```

On all applicable events:

```text
reconcile
reschedule
ensure monitoring where allowed
```

After app update:

```text
clear/rebuild scheduled alarms
```

because old PendingIntents must not control obsolete configuration.

Do not use:

```text
LOCKED_BOOT_COMPLETED
```

because direct-boot support is out of scope.

---

# 44. Foreground Service

For a Device Owner app targeting API 34, use the Device Owner-compatible
foreground-service path.

Declare the required system-exempted foreground-service permission
and service type.

Use:

```text
FOREGROUND_SERVICE
FOREGROUND_SERVICE_SYSTEM_EXEMPTED
```

and:

```xml
android:foregroundServiceType="systemExempted"
```

when supported by the target API.

Android explicitly lists Device Owner applications among the allowed
cases for `systemExempted`.

The service does not become the source of truth.

---

# 45. Device Owner Policy on Limiter Itself

When Device Owner is active:

```text
setUninstallBlocked(self, true)
setUserControlDisabled([self])
```

The goal is to prevent ordinary Settings-level:

```text
uninstall
clear-data
force-stop
```

of the Limiter application.

Do not disable:

```text
ADB
factory reset
safe boot
system recovery
```

These remain legitimate recovery mechanisms.

---

# 46. Stage 3 Tests

Create:

```text
FakeDpmGateway
FakeAppFreezer
FakeTimeSource
InMemoryLockStateStore
```

Mandatory tests:

### Device Owner

```text
no Device Owner
    -> no new logical enforcement lock
    -> explicit NotDeviceOwner
```

### Freezer

```text
suspend success + hide success
    -> Ok

suspend reports failed package
    -> Failed

suspend succeeds + hide fails
    -> Failed
    -> logical lock remains
    -> next reconcile retries

hidden-only physical state
    -> isFrozen false
    -> repair

suspended-only physical state
    -> isFrozen false
    -> repair
```

### Lock state

```text
lock never shortens
expired lock unfreezes
failed unfreeze keeps lock
retry backoff
successful retry clears backoff
missing package clears stale lock
```

### Protected package

```text
new protected package cannot be locked
already locked protected package expires normally
physically frozen + protected + no record
    -> recover/unfreeze
```

### Pending settings

```text
loosening created today
    -> not eligible today

next local day
    -> eligible

locked package
    -> remains pending

package unlocks during reconcile
    -> pending applies same reconcile cycle

latest pending update replaces older pending update
```

### Daily limit

```text
limit reached before midnight
    -> lock through appropriate reveal/midnight boundary

limit reached shortly after midnight
    -> lock until next local midnight

normal limit during daytime
    -> no reveal-time flapping
```

### Alarm retry

```text
expired lock + permanent unfreeze failure
    -> bounded retry intervals
    -> no past-due tight alarm loop
```

### Usage

```text
multiple activities
overlap
orphan pause
orphan stop
stop event
shutdown event
cross-midnight activity
still-active activity
out-of-window events
```

---

# 47. Mandatory Device Integration Test — after Stage 3

JVM tests do NOT establish Android enforcement correctness.

On the actual test phone:

```text
1. factory-reset/device-owner provision
2. grant Usage Access
3. grant notifications
4. verify Limiter is Device Owner
5. verify uninstall is blocked
6. verify user-control restriction
7. add a harmless test application
8. configure short daily limit
9. consume until limit
10. verify app is suspended
11. verify app is hidden
12. verify app cannot launch
13. reboot while locked
14. verify lock survives
15. stop/restart monitoring
16. verify reconcile repairs state
17. verify cross-midnight window
18. hit limit shortly after midnight
19. verify no 07:00 flapping/relock bug
20. change timezone through the supported test procedure
21. verify schedule recalculation
22. allow lock to expire
23. verify app becomes visible and unsuspended
24. make a target become protected where safely testable
25. verify an existing lock does not brick the device
26. verify pending changes apply after eligibility
27. verify a missing target package does not leave stale lock state
```

Do not declare Stage 3 complete until these tests have been manually
performed and written into:

```text
STAGE3_DEVICE_TEST.md
```

---

# 48. Technical reference D — Minimal UI (original Stage 4)

Stage 4 is intentionally small.

Do NOT add:

```text
widget
streak
reflection
gamification
analytics
history dashboards
```

yet.

Use Compose.

Screens:

```text
Onboarding
Home
AppPicker
AppSettings
LockedReadOnly
```

---

# 49. Onboarding

Show:

```text
Device Owner
Usage Access
Notifications
Exact Alarm
Device Policy
```

Refresh status on every `ON_RESUME`.

Do not estimate account count through `AccountManager`.

Show the Device Owner command:

```bash
adb shell dpm set-device-owner com.limiter.app/.admin.LimiterAdminReceiver
```

Show recovery guidance only in a clearly marked setup/help section.

---

# 50. Home

For each target:

```text
app label
used / limit
remaining
locked until
```

Use `remainingMillis` internally.

Display minutes only as a presentation value.

---

# 51. App Picker

Show only:

```text
launchable
installed
non-system
non-protected
not already selected
```

Default configuration:

```text
limit = 60 minutes
hide = 23:00
reveal = 07:00
```

All additions go through:

```text
LockCoordinator.tryEditConfig(Add)
```

---

# 52. App Settings

Before displaying editable controls:

```text
collect LockState
```

Branch:

```text
LockedUntil
    -> LockedReadOnlyScreen

Unlocked
    -> EditableSettingsScreen
```

Locked screen contains only:

```text
lock end
countdown
Back
```

No edit/remove/unlock/snooze controls.

All ViewModel mutations go through:

```text
tryEditConfig()
```

---

# 53. Stage 4 Tests

Test:

```text
OnboardingState
locked screen branching
edit rejected while locked
picker filtering
protected package filtering
deferred-edit message
Device Owner missing state
```

Run:

```bash
./gradlew assembleDebug testDebugUnitTest
```

Create:

```text
STAGE4_NOTES.md
```

---

# 54. Optional Stage 5

Only after Stages 1–4 and the Device Integration Test are stable.

Stage 5 may add:

```text
Grace notifications
Reflection log
History
Streak
Widget
```

but none of them may modify lock semantics.

They must consume:

```text
LockCoordinator events
UsageSnapshot
HistoryRepository
```

and never own:

```text
freeze
unfreeze
unlock
```

No release CI is required.

No cloud synchronization is required.

No analytics is required.

No network permission is allowed.

---

# 55. Final Static Review

Before final completion, run repository-level checks for:

```text
INTERNET permission
```

and reject if present.

Search for direct framework freeze calls outside the DPM/freezer layer.

Reject:

```text
setApplicationHidden
setPackagesSuspended
```

outside the gateway/freezer implementation.

Search for:

```text
System.currentTimeMillis()
Instant.now()
ZonedDateTime.now()
```

inside business logic.

Search for forbidden APIs/names:

```text
unlock(
forceUnlock(
skip(
snooze(
removeWhileLocked
debugUnlock
```

Review that:

```text
only LockCoordinator owns AppFreezerApi
only reconcile creates Unlocked
no configuration shortens LockedUntil
pending changes use eligibleFromEpochDay
expired lock retry uses backoff
missing packages cannot remain locked
protected packages cannot receive new locks
```

---

# 56. Final Build

The final local build must pass:

```bash
./gradlew assembleDebug testDebugUnitTest
./gradlew assembleRelease
```

`assembleRelease` must use the same local keystore as debug.

No CI release workflow is required.

---

# 57. Final Deliverables

```text
AGENTS.md
STAGE1_NOTES.md
STAGE2_NOTES.md
STAGE3_NOTES.md
STAGE3_DEVICE_TEST.md
STAGE4_NOTES.md
FINAL_README.md
```

`FINAL_README.md` must document:

```text
Device Owner provisioning
testOnly debug recovery
package-state recovery
Usage Access
notification permission
exact alarm status
local signing key
keystore backup requirement
lock semantics
daily limit semantics
cross-midnight semantics
pending setting semantics
failure/retry behavior
Device Owner limitations
known device-specific assumptions
```

Never claim "unbreakable".

The correct claim is:

```text
The system is designed so that persisted logical state,
actual Android package state, and reconciliation converge
under normal supported Android Device Owner operation.
```

---

# Appendix H — Historical Stage Notes supplied by the human

These notes are transcribed into the canonical instructions so a fresh agent sees the history in one place. They are not proof of current implementation or test status. Preserve the original files in the repository if they exist; do not replace them with this appendix.

## H1. Original Stage 1 Notes (historical)

### Created (reported, not re-verified here)
- `settings.gradle.kts`
- `build.gradle.kts`
- `gradle.properties`
- `.gitignore` (ignoring local config and keys)
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/debug/AndroidManifest.xml` (with `testOnly="true"`)
- `app/src/main/java/com/limiter/app/MainActivity.kt`
- `app/src/main/java/com/limiter/app/admin/LimiterAdminReceiver.kt`
- `app/src/main/res/xml/device_admin.xml`
- `app/src/test/java/com/limiter/app/Stage1SanityTest.kt`
- `.github/workflows/build.yml`
- Generated local `limiter.jks` and `keystore.properties`
- `AGENTS.md` (renamed from `AGENENTS.md`)

### Original design decisions (historical; signing decision requires audit)
- Fall-safe interpretation: GitHub Actions workflow reportedly requires a dummy keystore generated on-the-fly to successfully build `assembleDebug` since `keystore.properties` is ignored. This conflicts with the same-key signing invariant in Section B4. Inspect the real workflow in R0 and do not accept the dummy-key approach as canonical signing continuity.
- JDK 17 was reported as installed locally and used for a Gradle build at that time.

### Original build results (historical claim only)
- `assembleDebug` and `testDebugUnitTest` were reported as successful in the Stage 1 session. This is not current verification; rerun in the present environment.
- Local signing properties were reportedly enforced gracefully. Verify actual Gradle behavior without exposing secret values.

## H2. Original Stage 2 Notes (historical)

### What Stage 2 reportedly implemented
Stage 2 focused on core data models, pure usage calculators, pure settings-policy logic, DataStore integration, TimeMath utilities, a usage-monitoring base service, and dynamic resolution of protected critical packages.

### What was reportedly already present when Gemini took over
The repository was said to be nearly fully populated with Stage 2 files by a prior Claude Opus agent. The models, policies, calculators, time math, store architecture, and tests were said to be created. The Android SDK and JDK environment in the agent context were reported missing or improperly configured.

### Components reported as implemented
- **Data models:** `TargetAppConfig`, `LockState`, `PendingChange`, `UsageEventRecord`, `UsageSnapshot`
- **Settings and persistence:** `SettingsRepository`, `UsageSnapshotStore`, `Codec`, `Stores`
- **Business logic:** `UsageCalculator`, `SettingsPolicy`, `TimeMath`, `PollPlanner`
- **System integration:** `DeviceCriticalPackageResolver`, `UsageStatsReader`, `UsageMonitorService`
- **Tests:** `TimeMathTest`, `SettingsPolicyTest`, `UsageCalculatorTest`, `CodecTest`, `PollPlannerTest`, `CriticalPackageResolverTest`
- **Manifest:** permission mapping was reported as correct for the original Stage 2 scope.

### Code preservation and patch claims
- Nearly all Kotlin files from Claude were said to be preserved unchanged and to follow pure functional patterns without hidden time/device dependencies.
- `build.gradle.kts` was reportedly patched to restore `compileSdk` and `targetSdk` to `34`.
- A `check-environment.sh` script was reportedly created to verify Java, Android SDK, `compileSdk 34`, and Gradle.

### Tests and environment blockers (important)
- **No JVM tests were run** in that session, despite the listed test files.
- Gradle could not run because the agent context reportedly lacked JDK 17. Default Java was OpenJDK 25.
- `apt-get install openjdk-17-jdk` reportedly stalled due slow downloads (about 135 kB/s).
- SDKMAN installation of Eclipse Temurin 17 reportedly failed with network/repository timeouts.
- Android SDK validation could not be completed dynamically.
- Stage 3 was explicitly reported as **not started**.

### Original design decisions
- Pure Kotlin classes were intended to remain decoupled from Android framework classes.
- Production `DeviceCriticalPackageResolver` was intended to be separate from fake resolver implementations used by tests.

## H3. Original Recovery Notes (historical)

### Environment persistent configuration
The build environment should exist outside the Git repository and persist across chat sessions.

**JDK 17**
- Required version: JDK 17.
- Previous status in the Stage 2 agent context: not installed cleanly due `apt-get` throttling and SDKMAN network failures.
- The previous notes said the human should manually run `sudo apt-get install openjdk-17-jdk` or install Eclipse Temurin 17.
- Example `JAVA_HOME` for a Debian/Ubuntu OpenJDK installation:
  ```bash
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
  ```

**Android SDK**
- Expected installation location: `$HOME/Android/Sdk`.
- Required components: `compileSdk 34`, `targetSdk 34`, build-tools `34.0.0`.
- Verification command: `./scripts/check-environment.sh`.

**Recovery from failed setup**
1. Verify `java -version` returns exactly a 17.x release.
2. Run `./scripts/check-environment.sh` to validate JDK/SDK constraints.
3. Resolve blockers reported by that script before handing the workspace to another agent.
## G. Observed on the real phone (human-reported, unverified)
- No Usage Access prompt/button anywhere in the app.
- "Add app" opens the Android file picker; package name typed by hand.
- Limit entered as raw minutes; no time picker.
- No app is ever locked; foreground service may not be running.
Treat as symptoms to explain in R0, not as confirmed causes.
These are historical setup expectations, not proof that the same paths/packages exist on the current machine. Verify before relying on them.

