# پرامپت‌های مرحله‌ای ساخت اپ Limiter (نسخه‌ی ضدگیر/ضدلوپ)

راهنما: این ۵ پرامپت رو **یکی‌یکی و به ترتیب** به Opus/Antigravity بده. هر مرحله باید قبل از رفتن به مرحله‌ی بعد با موفقیت کامپایل بشه. هر پرامپت خودش یه بلوک "اگه گیر کردی" داره که از لوپ بی‌نهایت جلوگیری می‌کنه.

---

## مرحله ۱ — Scaffold + Device Admin پایه

```
You are building stage 1 of 5 for an Android app called "Limiter" 
(Kotlin, self-imposed app usage restriction tool for personal use, 
offline-only, no ads/analytics/network).

### Scope of THIS stage only
- Create the Gradle project scaffold.
- Create the DeviceAdminReceiver subclass and its device_admin.xml policy 
  file.
- Create a minimal AndroidManifest.xml registering it.
- Do NOT implement UsageStatsManager, AlarmManager, Compose UI, or 
  freezing logic yet — those are later stages. A minimal MainActivity 
  with just a placeholder Text/TextView is enough for this stage.

### Fixed, pinned versions — do not upgrade these on your own
- Kotlin 1.9.24
- Android Gradle Plugin (AGP) 8.5.2
- Gradle wrapper 8.7
- compileSdk = 34, targetSdk = 34, minSdk = 26
If any dependency conflict forces a change, stop and report the exact 
conflict instead of trying multiple version combinations by trial and 
error.

### Hard constraints to prevent wasted cycles
1. Several DevicePolicyManager APIs used later in this project (e.g. 
   setApplicationHidden, addUserRestriction) throw SecurityException 
   unless the app is an actual Device Owner, which can ONLY be granted 
   by the end user running an adb command on a real physical device — 
   this cannot be simulated in an emulator or CI. For THIS and ALL 
   future stages: verify code compiles correctly, but do NOT attempt to 
   runtime-execute or instrumentation-test any Device Owner API call. 
   If you find yourself trying to "fix" a SecurityException from these 
   specific APIs by changing the code, STOP — that is expected 
   behavior in a non-Device-Owner test environment, not a bug.
2. If a build error repeats after your 2nd fix attempt for the SAME 
   root cause, stop and output a clear written diagnosis (what failed, 
   what you tried, why you think it's failing) instead of attempting a 
   3rd variation. Trial-and-error past 2 attempts on the same error 
   wastes resources without new information.
3. Do not invent or guess at Android API method signatures you are not 
   certain exist. If uncertain whether a method/class exists (this is 
   especially likely for less-common DevicePolicyManager methods used 
   in later stages), say so explicitly rather than writing plausible-
   looking code that may not compile.

### Deliverable for this stage
- Full Gradle project that runs `./gradlew assembleDebug` successfully.
- A short STAGE1_NOTES.md listing exactly which files were created and 
  confirming the build passed.

### If you get stuck
If after reasonable effort (a few attempts) something in this stage 
cannot be completed, do NOT keep retrying indefinitely. Stop, write a 
clear summary in STAGE1_NOTES.md of: what's blocking you, what you 
tried, and what information/decision you need from the human to 
proceed. A clear stop-and-report is always better than silent looping 
or a broken partial state.
```

---

## مرحله ۲ — UsageStatsManager + AppFreezer

```
You are building stage 2 of 5 for the "Limiter" Android app. Stage 1 
(project scaffold + DeviceAdminReceiver) is already complete and 
compiles — build on top of it, do not restructure what already exists 
unless strictly necessary, and explain why if you do.

### Scope of THIS stage only
- Implement UsageMonitorService: a foreground Service that polls 
  UsageStatsManager every 2–5 minutes for cumulative daily foreground 
  time of a configurable list of target package names.
- Implement AppFreezer: a small wrapper module around 
  DevicePolicyManager.setApplicationHidden() and 
  setPackagesSuspended(), exposing simple hide(pkg)/unhide(pkg)/
  suspend(pkg)/unsuspend(pkg) functions. Do not call these from UI yet 
  — that comes in a later stage.
- Do NOT implement AlarmManager scheduling, Compose UI, or the lock 
  state machine yet.

### Android-version-specific details you must get right
- targetSdk = 34 (Android 14): any foreground Service MUST declare 
  android:foregroundServiceType="specialUse" in the manifest AND 
  include the required 
  <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" 
  android:value="usage_limiting"/> inside the <service> tag. Missing 
  this causes a runtime crash on API 34+ that will NOT show up as a 
  compile error — get this right the first time rather than discovering 
  it later.
- PACKAGE_USAGE_STATS is a special permission the user must grant 
  manually via Settings > Usage Access — it cannot be requested via a 
  normal runtime permission dialog. Your code should check for it via 
  AppOpsManager and show an explanatory screen with a button that opens 
  Settings.ACTION_USAGE_ACCESS_SETTINGS, but do NOT attempt to test 
  whether usage stats actually return data in this environment — that 
  requires the permission granted on a physical device.

### Hard constraints (same as stage 1 — repeated because they matter)
1. Do not attempt to runtime-verify DevicePolicyManager or 
   UsageStatsManager behavior in an emulator/CI — compile-time 
   correctness only for these specific APIs.
2. Max 2 fix attempts per distinct root-cause error, then stop and 
   report.
3. Do not guess at uncertain API signatures — flag uncertainty instead.

### Deliverable for this stage
- UsageMonitorService.kt, AppFreezer.kt, updated AndroidManifest.xml.
- Project still builds with `./gradlew assembleDebug`.
- STAGE2_NOTES.md confirming build passed and listing any assumptions 
  made (e.g. default polling interval used).

### If you get stuck
Same as stage 1: stop, don't loop, write a clear blocker summary in 
STAGE2_NOTES.md instead of retrying indefinitely.
```

---

## مرحله ۳ — Lock State Machine + AlarmManager (قلب پروژه)

```
You are building stage 3 of 5 for the "Limiter" Android app — this is 
the most important stage. Stages 1–2 (scaffold, DeviceAdminReceiver, 
UsageMonitorService, AppFreezer) already exist and compile.

### Scope of THIS stage only
- Implement LockStateRepository: persists per-target-app state as 
  either UNLOCKED or LOCKED_UNTIL:<epoch timestamp>, using Room or 
  DataStore (your choice, but be consistent).
- Implement TimeWindowScheduler: wraps AlarmManager to set daily 
  hide/reveal alarms at fixed times (start/end configurable per target 
  app).
- Wire UsageMonitorService (from stage 2) and TimeWindowScheduler to 
  BOTH call LockStateRepository to transition state — never call 
  AppFreezer directly from either of them. LockStateRepository is the 
  ONLY thing allowed to call AppFreezer's hide/unhide functions.
- The ONLY code path allowed to transition a target app from LOCKED 
  back to UNLOCKED is the AlarmManager callback tied to that specific 
  unlock timestamp. Do not add any other function, broadcast receiver, 
  or exported component that can change LOCKED state early. This is a 
  deliberate design constraint, not an oversight — do not "helpfully" 
  add a way to unlock early, an admin override, or a reset function, 
  even if it would seem convenient.

### Android-version-specific details
- API 31+ (Android 12+) requires the SCHEDULE_EXACT_ALARM permission 
  for setExactAndAllowWhileIdle(). From API 33+ this must be granted by 
  the user manually via Settings (not a runtime dialog). Add the 
  manifest permission and a check + Settings-launch flow, but do not 
  runtime-test whether alarms actually fire in this environment.
- Add a BOOT_COMPLETED receiver that reschedules all pending alarms 
  after device reboot, since AlarmManager alarms do not survive reboots 
  on their own.

### Hard constraints (repeated — these matter most in this stage)
1. Do not runtime-test AlarmManager firing, DevicePolicyManager calls, 
   or UsageStatsManager reads in this environment — compile-time 
   correctness only.
2. Max 2 fix attempts per distinct root-cause error, then stop and 
   report rather than trying a 3rd variation.
3. Do not guess at uncertain API signatures.
4. Do not add any bypass, override, snooze, or early-unlock mechanism 
   under any justification (convenience, testing ease, "just in case"). 
   If you believe one is needed for testability, note that in 
   STAGE3_NOTES.md as a question for the human instead of adding it to 
   the code.

### Deliverable for this stage
- LockStateRepository.kt, TimeWindowScheduler.kt, BootReceiver.kt, 
  updated manifest.
- Project still builds with `./gradlew assembleDebug`.
- STAGE3_NOTES.md confirming build passed.

### If you get stuck
Same as previous stages: stop and report clearly rather than looping.
```

---

## مرحله ۴ — رابط کاربری (Jetpack Compose)

```
You are building stage 4 of 5 for the "Limiter" Android app. Stages 
1–3 (Device Owner setup, usage monitoring, freezing, and the lock state 
machine) already exist and compile.

### Scope of THIS stage only
- Build the Jetpack Compose UI: target-app picker, per-app settings 
  (daily cap in minutes, time window start/end, hide-vs-suspend mode), 
  and the main screen.
- CRITICAL rule carried over from stage 3's design: before rendering 
  ANY interactive settings screen for a given target app, the UI layer 
  MUST first read that app's state from LockStateRepository. If the 
  state is LOCKED_UNTIL:<timestamp>, render ONLY a read-only screen 
  showing "Locked until <formatted time>" — do not render the settings 
  form at all, not even in a disabled/grayed-out state. The settings 
  Composable function itself should branch at the top: if locked, 
  return the read-only Composable; only otherwise proceed to the 
  editable form. This must be a structural code branch, not a visual 
  styling difference.
- Onboarding flow: a first-run checklist screen showing (a) current 
  Google/other account count via AccountManager with a link to 
  Settings > Accounts, and (b) the exact adb command to run once ready, 
  displayed as selectable/copyable text.

### Dependency management to avoid version-mismatch build failures
- Use the Compose BOM (platform("androidx.compose:compose-bom:<latest 
  stable BOM version you are confident is correct>")) for all Compose 
  library versions rather than pinning individual Compose library 
  versions by hand — this avoids the most common cause of Compose 
  build failures (mismatched compiler/runtime versions). If unsure of 
  the exact current stable BOM version string, use 2024.06.00 as a 
  known-good baseline rather than guessing a newer one that may not 
  exist.

### Hard constraints (repeated)
1. Do not runtime-test Device Owner / usage-stats / alarm behavior — 
   compile-time correctness only. Compose UI preview/compile checks are 
   fine and encouraged.
2. Max 2 fix attempts per distinct root-cause error, then stop and 
   report.
3. Do not guess at uncertain API signatures.
4. Do not add any settings path, button, or gesture that could unlock a 
   LOCKED target app outside the AlarmManager-driven transition — 
   including in this UI stage. If a locked screen "feels incomplete" 
   without an unlock button, that absence is intentional; do not add 
   one.

### Deliverable for this stage
- Compose screens, navigation, ViewModels as needed.
- Project still builds with `./gradlew assembleDebug`.
- STAGE4_NOTES.md confirming build passed.

### If you get stuck
Same as previous stages: stop and report clearly rather than looping.
```

---

## مرحله ۵ — فیچرهای جانبی + Build نهایی APK

```
You are building stage 5 of 5 (final stage) for the "Limiter" Android 
app. Stages 1–4 are complete and compile: Device Owner setup, usage 
monitoring + freezing, the lock state machine, and the Compose UI.

### Scope of THIS stage only
- Grace-period notifications (10 min / 2 min before a scheduled lock).
- Simple reflection-log: on lock transition, a notification with 2–3 
  quick-reply actions, stored with timestamp in the existing local DB.
- Streak calendar view (read-only, computed from stored usage history — 
  no new write paths to lock state).
- Home screen widget showing remaining minutes for the primary target 
  app today.
- A GitHub Actions workflow file (.github/workflows/build.yml) that 
  runs `./gradlew assembleDebug` on push and uploads the resulting APK 
  as a build artifact.

### CI/build constraints to prevent workflow failures
- Use the default debug signing config only. Do NOT create or reference 
  a release keystore, do NOT add any keystore secrets to the workflow — 
  this is a personal-use sideloaded app, release signing is out of 
  scope and unnecessary complexity.
- Explicitly accept Android SDK licenses in the workflow (e.g. via 
  `yes | sdkmanager --licenses` or the equivalent standard GitHub Action 
  step) — omitting this is a common, easy-to-miss cause of CI failures 
  that has nothing to do with the app's own code.
- Pin the exact same Kotlin/AGP/Gradle/SDK versions used in stage 1 — 
  do not let the CI environment silently use different versions than 
  local development.

### Hard constraints (repeated, final time)
1. Do not runtime-test Device Owner / usage-stats / alarm behavior 
   locally or in CI — compile-time and CI-build success only. The CI 
   workflow's job is to confirm the APK builds, not to exercise Device 
   Owner logic (which is impossible in CI regardless).
2. Max 2 fix attempts per distinct root-cause error, then stop and 
   report.
3. Do not guess at uncertain API or GitHub Actions syntax — flag 
   uncertainty instead of writing plausible-looking but unverified YAML.
4. Do not add any lock-bypass mechanism, in this stage or by modifying 
   earlier stages' code.

### Deliverable for this stage
- All stage-5 features implemented.
- Working GitHub Actions workflow producing a debug APK artifact.
- A final README.md covering: Device Owner ADB setup steps (zero-
  account requirement), how to add/remove target apps, where daily cap 
  / time window settings live, and a link to (or copy of) the build 
  artifact location in Actions.
- FINAL_NOTES.md summarizing the whole build across all 5 stages: what 
  was built, any deviations from the original spec and why, and any 
  known limitations.

### If you get stuck
Same as previous stages: stop and report clearly in FINAL_NOTES.md 
rather than looping indefinitely.
```

---

## نکات نهایی برای خودت (نه برای Opus)

- **بین هر مرحله**، قبل از دادن پرامپت بعدی، مطمئن شو که خروجی مرحله‌ی قبل واقعاً `./gradlew assembleDebug` رو رد کرده (خود ایجنت باید تاییدش کنه تو NOTES.md، ولی اگه شک داشتی خودت هم یه نگاه بنداز).
- اگه یه مرحله با «Max 2 fix attempts» متوقف شد و تو NOTES.md یه سؤال/بلاکر نوشت، یعنی دقیقاً همون رفتاری که می‌خواستیم رخ داده (به‌جای سوزوندن کردیت رو تلاش‌های بی‌فایده) — اون بلاکر رو بخون، تصمیم بگیر، و با یه پرامپت کوتاه اصلاحی ادامه بده، نه اینکه کل مرحله رو از اول بخوای.
- اگه یه مرحله gpt/Opus چیزی رو hallucinate کرد (یه متد/کلاسی که وجود نداره)، این معمولاً رو APIهای کمتر رایج مثل `setApplicationHidden` رخ میده — یه جستجوی سریع تو مستندات رسمی `developer.android.com` می‌تونه سریع تاییدش کنه قبل از ادامه.
