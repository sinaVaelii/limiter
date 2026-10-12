# Limiter — Staged Prompts (v2)

## نحوه‌ی استفاده

1. **Step 0** (پایین) را خودت دستی انجام بده.
2. متن بلوک «AGENTS.md» را در ریشه‌ی پروژه ذخیره کن. همه‌ی قانون‌های مشترک آن‌جاست و هر مرحله اول آن را می‌خواند، پس لازم نیست در هر پرامپت تکرار شوند.
3. مراحل ۱ تا ۵ را **جدا و به ترتیب** بده. قبل از رفتن به مرحله‌ی بعد، `STAGEn_NOTES.md` و نتیجه‌ی build را خودت چک کن.
4. پرامپت‌ها انگلیسی‌اند (برای agent)، توضیحات فارسی است.

### تصمیم‌هایی که من گرفتم (هر کدام را می‌توانی عوض کنی)

| تصمیم | توضیح |
|---|---|
| قفل = suspend **و** hide همزمان | اگر کسی با `pm unhide` فقط hide را بردارد، suspend هنوز هست. `reconcile` هر tick وضعیت قفل را دوباره اعمال می‌کند. |
| تنظیم ساعت سیستم بسته می‌شود | `DISALLOW_CONFIG_DATE_TIME` و auto-time اجباری، چون timestamp قفل به ساعت دیواری وابسته است. |
| flagهای `FACTORY_RESET` / `SAFE_BOOT` / `DEBUGGING_FEATURES` پیش‌فرض **خاموش** | `DEBUGGING_FEATURES` خود adb را می‌بندد و آپدیت/حذف Device Owner را سخت می‌کند. وقتی اپ نهایی شد روشنش کن. |
| سست‌کردن تنظیمات دیر اعمال می‌شود | بالا بردن limit، تغییر ساعت‌ها و حذف اپ بعد از نیمه‌شب اعمال می‌شود. سخت‌تر کردن (پایین آوردن limit) فوری است. |
| `USE_EXACT_ALARM` | جریان Settings برای exact alarm فقط برای API 31–32 می‌ماند. |
| شمارش account با `AccountManager` حذف شد | روی API 26+ برنامه‌ها نمی‌توانند accountهای بقیه را ببینند و عدد گمراه‌کننده می‌شود. به‌جایش دستور `adb shell dumpsys account` نمایش داده می‌شود. |
| ویجت با RemoteViews | نه Glance، برای کم کردن ریسک نسخه‌ها. |
| نوتیف reflection با دکمه‌های ثابت | متن آزاد (RemoteInput) ندارد. |
| polling تطبیقی | معمولاً ۵ دقیقه، ولی وقتی ≤۱۲ دقیقه مانده باشد هر ۳۰ ثانیه، تا نوتیف «۲ دقیقه مانده» معنی داشته باشد. |
| امضا | debug فقط برای verify در CI. برای نصب روی گوشی، release با keystore ثابت (مرحله ۵). |
| CI از مرحله ۱ | مرحله ۵ فقط امضا و release را اضافه می‌کند. |

---

## Step 0 — کارهای دستی قبل از شروع

- JDK 17 و Android SDK (platform 34، build-tools 34.0.0) نصب باشد و `ANDROID_HOME` تنظیم باشد.
- یک پوشه‌ی خالی (ترجیحاً git repo) بساز و `AGENTS.md` را در آن بگذار.
- اگر Gradle نصب داری، در آن پوشه `gradle wrapper --gradle-version 8.7` بزن. فایل `gradle-wrapper.jar` باینری است و agent نمی‌تواند بسازدش.
- قبل از روشن کردن flagهای سخت‌گیرانه (بخش hardening) روی emulator یا گوشی دوم امتحان کن.

---

## AGENTS.md (ریشه‌ی پروژه)

```text
# Limiter — project rules (read this first, every stage)

## What this is
"Limiter": personal-use, offline-only Android app (Kotlin) that locks chosen apps when a daily
limit is reached or a nightly window starts. Runs as Device Owner. No ads, analytics or network
code. Do NOT declare the INTERNET permission.

## Fixed conventions (do not rename)
- applicationId / namespace: com.limiter.app ; subpackages: admin, model, data, usage, service,
  freezer, lock, alarm, history, ui, notify, widget, util
- Application class: com.limiter.app.LimiterApp — a simple service locator (lazy singletons + an
  app-scope CoroutineScope). No DI framework. No heavy work in onCreate.
- Device admin receiver: com.limiter.app.admin.LimiterAdminReceiver
  Device Owner component string: com.limiter.app/.admin.LimiterAdminReceiver
- Gradle Kotlin DSL (*.gradle.kts), no version catalog.
- Time is abstracted behind `interface TimeSource { fun nowMillis(): Long; fun zone(): ZoneId }`
  (util/TimeSource.kt). Tests use a FakeTimeSource. Never call System.currentTimeMillis() in logic.
- Times of day are stored as Int "minute of day" (0..1439). Use java.time (native on minSdk 26).

## Pinned versions (never upgrade on your own)
Kotlin 1.9.24 · AGP 8.5.2 · Gradle 8.7 · JDK 17 · compileSdk 34 · targetSdk 34 · minSdk 26
Compose BOM 2024.06.00 · composeOptions.kotlinCompilerExtensionVersion 1.5.14
androidx.core:core-ktx 1.13.1 · androidx.activity:activity-compose 1.9.0
androidx.lifecycle:lifecycle-runtime-compose / lifecycle-viewmodel-compose 2.8.3
androidx.datastore:datastore-preferences 1.1.1
kotlinx-coroutines-android / kotlinx-coroutines-test 1.8.1 · junit 4.13.2
If dependency resolution forces a change to an AndroidX/coroutines version, choose the nearest
compatible one and record it in the stage notes. Changing Kotlin, AGP, Gradle, JDK or compileSdk
is forbidden: report the exact conflict and stop.

## Rules of engagement
1. NEVER run the app, an emulator, adb, or any Device Owner / UsageStatsManager / AlarmManager
   call at runtime. "Correct" means: it compiles AND the JVM unit tests pass
   (`./gradlew assembleDebug testDebugUnitTest`).
2. Keep logic testable: business logic is pure Kotlin (no android.* types) behind small
   interfaces (AppFreezerApi, TimeSource, LockStateStore, ...). Android wrappers stay thin.
   Unit tests use fakes. Tests are a required deliverable of every stage that has logic.
3. NEVER guess an Android API. Verify every framework method you call:
   javap -classpath "$ANDROID_HOME/platforms/android-34/android.jar" <fully.qualified.Class>
   For the API level a member was added, grep "$ANDROID_HOME/platforms/android-34/data/api-versions.xml"
   (if present) and guard with Build.VERSION.SDK_INT when it is above 26.
4. Retry rule: at most 3 attempts per distinct root cause, and every attempt must test a DIFFERENT
   hypothesis. Never repeat the same fix. Then use the stop protocol.
5. Stop protocol: (a) stop making code changes, (b) write the blocker in the current stage notes
   under "Blockers" (each attempt, the exact error, your best hypothesis, what the human must
   decide), (c) end your turn with a final message that starts with "BLOCKED:". Do not look for a
   special exit tool. Do not keep retrying.
6. Stay inside the stage scope. Do not implement things assigned to later stages.
7. If the folder is a git repo, commit at the end of each successful stage ("stage N").
8. DataStore: exactly ONE instance per file. Declare every `preferencesDataStore` delegate at top
   level in a single file data/Stores.kt and reuse it everywhere (receivers included).
9. No hidden APIs, no reflection tricks, no System.exit.

## Lock invariants ("no bypass") — every stage must preserve these
I1. Only LockCoordinator holds an AppFreezerApi. Nothing else freezes or unfreezes an app.
I2. The ONLY transition to Unlocked happens inside LockCoordinator.reconcile(), and only when
    now >= lockedUntil. The check lives inside LockStateStore, not in the caller. reconcile() is
    triggered by: alarm callback, boot / package-replaced / time-change receivers, monitor tick.
I3. lockedUntil is monotonic: a new lock may extend it, never shorten it.
I4. No API, UI, intent, broadcast, notification action or debug hook anywhere can unlock, skip,
    snooze, shorten, or edit (including remove) a locked app. All config edits go through
    LockCoordinator.tryEditConfig, which rejects locked packages.
I5. Packages in CriticalPackages can never be targets.
I6. Loosening config changes are deferred until after the next local midnight (see Stage 2).
I7. Editing config never changes an existing lock.

## Stage notes format (STAGEn_NOTES.md — required sections)
1. Files created/changed
2. Public interfaces (class + key signatures) later stages depend on
3. Manifest: permissions and components added
4. Dependencies added (with versions)
5. Commands run and results (`./gradlew assembleDebug testDebugUnitTest` tail)
6. Decisions / deviations from the prompt
7. Blockers (or "none")
```

---

## مرحله ۱ — Scaffold + Device Admin + Compose config + CI (verify)

```text
You are building stage 1 of 5 for the Android app "Limiter". Read AGENTS.md first and follow it
strictly (retry rule, stop protocol, pinned versions).

### Preflight (before writing any file)
1. `java -version` must be 17. ANDROID_HOME (or ANDROID_SDK_ROOT) must point to an SDK containing
   platforms/android-34 and build-tools 34.x. If something is missing and `sdkmanager` exists, make
   ONE install attempt (accept licenses, then install "platforms;android-34" and
   "build-tools;34.0.0"). If it still fails -> stop protocol.
2. Gradle wrapper: if gradlew and gradle/wrapper/gradle-wrapper.jar exist, keep them and make sure
   gradle-wrapper.properties uses gradle-8.7-bin.zip. Otherwise, if a `gradle` executable exists,
   run `gradle wrapper --gradle-version 8.7`. Otherwise write gradle-wrapper.properties only, state
   in the notes that the human must generate the wrapper jar (you cannot create binary files) and
   use the stop protocol.

### Scope of THIS stage
A. Gradle scaffold: settings.gradle.kts (google() + mavenCentral() in pluginManagement and
   dependencyResolutionManagement), root build.gradle.kts (plugins with the pinned versions,
   apply false), app/build.gradle.kts, gradle.properties (android.useAndroidX=true,
   org.gradle.jvmargs=-Xmx2g), .gitignore (build, .gradle, .idea, local.properties, *.jks,
   *.keystore, keystore.properties).
B. app module: namespace/applicationId com.limiter.app; compileSdk/targetSdk 34; minSdk 26;
   Java and Kotlin target 17; buildFeatures { compose = true };
   composeOptions { kotlinCompilerExtensionVersion = "1.5.14" } — set it NOW so later stages never
   touch build configuration. Dependencies for this stage only: core-ktx, activity-compose,
   Compose BOM + ui + material3 + ui-tooling-preview, debugImplementation ui-tooling, and
   testImplementation junit. Do NOT add DataStore, lifecycle or coroutines yet.
C. admin/LimiterAdminReceiver.kt: class LimiterAdminReceiver : DeviceAdminReceiver() with no
   behavior beyond optional logging. res/xml/device_admin.xml containing an EMPTY <uses-policies/>
   (no wipe, no reset-password, nothing else).
D. AndroidManifest.xml: <application android:allowBackup="false" ...> with MainActivity
   (launcher) and EXACTLY this receiver (BIND_DEVICE_ADMIN is an attribute on <receiver>, NOT a
   <uses-permission>):

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

E. MainActivity: ComponentActivity with setContent { Text("Limiter") }. Placeholder only.
F. One trivial unit test so `./gradlew testDebugUnitTest` demonstrably runs.
G. .github/workflows/build.yml — VERIFICATION ONLY. Triggers: push, pull_request,
   workflow_dispatch. Runner ubuntu-latest. Steps: actions/checkout@v4; actions/setup-java@v4
   (temurin, 17); accept licenses with
   `yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses || true`;
   `chmod +x gradlew`; gradle/actions/setup-gradle@v4; `./gradlew assembleDebug testDebugUnitTest
   --stacktrace`; actions/upload-artifact@v4 uploading app/build/outputs/apk/debug/*.apk.
   Do NOT declare any signingConfigs in this stage. You cannot run the workflow: only sanity-check
   the YAML syntax (for example with python3 -c "import yaml; yaml.safe_load(open(...))" if
   available).

### Out of scope
UsageStatsManager, AlarmManager, DataStore, freezing logic, real UI.

### Deliverable
`./gradlew assembleDebug testDebugUnitTest` passes; STAGE1_NOTES.md (format in AGENTS.md).
```

---

## مرحله ۲ — مدل داده + تنظیمات + پایش مصرف

```text
You are building stage 2 of 5 for "Limiter". Stage 1 is complete.

### First step
Read AGENTS.md, STAGE1_NOTES.md, app/build.gradle.kts and AndroidManifest.xml before writing code.

### Scope of THIS stage (no DevicePolicyManager code here, no alarms, no real UI)
A. model/
   - TargetAppConfig(packageName, dailyLimitMinutes, hideAtMinuteOfDay, revealAtMinuteOfDay) with
     validation: limit 1..1440, minute-of-day values 0..1439, hideAt != revealAt.
   - sealed LockState { Unlocked, LockedUntil(untilEpochMillis: Long) } (used in stage 3).
   - util/TimeSource.kt (interface + SystemTimeSource) and util/TimeMath.kt:
     nextOccurrence(nowMillis, zone, minuteOfDay): Long — the next instant STRICTLY after now.
     Use ZonedDateTime so DST gaps/overlaps resolve sanely.
B. data/CriticalPackages.kt: pure `CriticalPackages.isProtected(pkg, dynamic: Set<String>)` with a
   static set (settings, systemui, phone/telecom, dialer, messaging, permission controller,
   package installer, Google Play services/GSF, emergency, own package) plus
   `CriticalPackageResolver(context)` that adds dynamically resolved packages: default HOME
   (launcher), DIAL, default SMS, and Settings. Every launcher/dialer/SMS/Settings app that
   resolves must be protected.
C. data/SettingsRepository.kt (Preferences DataStore via data/Stores.kt) storing effective
   configs, pending changes and lastRolloverEpochDay. Put the rules in a PURE `SettingsPolicy`:
   - add(config): immediate; rejected if protected or duplicate.
   - edit(newConfig) for an existing package: lowering dailyLimitMinutes takes effect
     immediately; ANY other change (higher limit, different hide/reveal time) is stored as a
     pending Update (full desired config). If the edit is a mix, apply the tightening part now and
     defer the rest.
   - remove(pkg): stored as a pending Remove.
   - applyPending(snapshot, nowMillis, zone, lockedPackages: Set<String>): if the local epoch day is
     greater than lastRolloverEpochDay, apply all pending items EXCEPT those whose package is in
     lockedPackages (they stay pending), then set lastRolloverEpochDay.
   Persistence uses a pure-Kotlin string codec (NO org.json, NO serialization library) with
   round-trip tests. add/edit/remove/rollover on SettingsRepository are `internal`; only
   LockCoordinator (stage 3) may call them.
D. usage/
   - UsageEventRecord(packageName, className, type: RESUMED|PAUSED, timestampMillis).
   - UsageCalculator (pure): foregroundMillis(events, packageName, fromMillis, toMillis).
     Rules: a package counts as foreground while at least one of its activities is resumed;
     still-foreground at `to` counts up to `to`; a PAUSED with no RESUMED inside the window counts
     from `from`; events outside the window are ignored; sort events by timestamp first.
   - UsageStatsReader (thin wrapper over UsageStatsManager.queryEvents; maps event types 1 and 2;
     window = local midnight .. now via TimeSource). Do NOT use queryUsageStats(INTERVAL_DAILY).
   - UsageAccess.isGranted(context) via AppOpsManager (unsafeCheckOpNoThrow on API 29+,
     checkOpNoThrow below). Use OPSTR_GET_USAGE_STATS, or the literal "android:get_usage_stats" if
     the constant is not available for minSdk 26.
   - PollPlanner (pure): nextDelayMillis(minRemainingMinutes: Int?) -> null: 5 min; <=12: 30 s;
     <=30: 2 min; else 5 min.
   - UsageSnapshot / UsageSnapshotStore: an in-memory StateFlow with per-package usedMillis and
     remainingMinutes, plus states NoUsageAccess and Idle.
E. service/
   - interface MonitorCallbacks { suspend fun onTick(); suspend fun onLimitReached(pkg: String,
     usedMinutes: Int) } with a default no-op registered in LimiterApp (stage 3 replaces it).
   - UsageMonitorService: foreground service. Call ServiceCompat.startForeground(this, id,
     notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE). Low-importance notification
     channel. Tick loop in a coroutine: callbacks.onTick(); if usage access is missing, publish
     NoUsageAccess and retry after 60 s; otherwise for each effective config compute used and
     remaining, publish the snapshot, call callbacks.onLimitReached for every package at or over
     its limit (the receiver is idempotent), then delay(PollPlanner...).
   - ServiceStarter.ensureRunning(context): starts the service only if usage access is granted.
F. LimiterApp (registered as android:name in the manifest) exposing lazy singletons: TimeSource,
   SettingsRepository, CriticalPackageResolver, UsageSnapshotStore, monitorCallbacks.
G. Manifest additions (exactly): FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE,
   POST_NOTIFICATIONS, PACKAGE_USAGE_STATS (tools:ignore="ProtectedPermissions"),
   QUERY_ALL_PACKAGES (tools:ignore="QueryAllPackagesPermission"), and:

   <service
       android:name=".service.UsageMonitorService"
       android:exported="false"
       android:foregroundServiceType="specialUse">
       <property
           android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
           android:value="usage_limiting" />
   </service>

   Add coroutines, DataStore and kotlinx-coroutines-test to build.gradle.kts (pinned versions).

### Unit tests (required)
UsageCalculator (overlap of two activities, still-foreground, orphan pause, out-of-window, other
packages), PollPlanner, SettingsPolicy (tightening now vs loosening deferred, remove deferred,
locked packages stay pending, new-day rollover only), codec round-trips, CriticalPackages, TimeMath
(strictly-after, DST using ZoneId "Europe/Berlin", hideAt > revealAt across midnight).

### Deliverable
`./gradlew assembleDebug testDebugUnitTest` passes; STAGE2_NOTES.md (with the public interfaces
listed).
```

---

## مرحله ۳ — Freezer + Hardening + Lock state machine + Alarms (قلب پروژه)

```text
You are building stage 3 of 5 for "Limiter". This is the core stage. Read AGENTS.md (especially the
Lock invariants I1–I7), STAGE2_NOTES.md, the manifest, model/*, SettingsRepository and
UsageMonitorService before writing code.

### Scope of THIS stage
A. freezer/
   - interface AppFreezerApi { freeze(pkg): FreezeResult; unfreeze(pkg): FreezeResult;
     isFrozen(pkg): Boolean }, FreezeResult = Ok | NotDeviceOwner | Failed(message).
   - AppFreezer (real): freeze = setPackagesSuspended(true) AND setApplicationHidden(true);
     unfreeze = setApplicationHidden(false) AND setPackagesSuspended(false); isFrozen =
     isApplicationHidden. Guard with dpm.isDeviceOwnerApp(packageName) first (return NotDeviceOwner,
     never throw); catch SecurityException/IllegalArgumentException -> Failed.
     Never call these at runtime in tests: the real class is compile-checked only.
   - HardeningPolicy + HardeningConfig(data class with defaults) applied only when Device Owner:
     ON by default: setUserControlDisabledPackages(own package) on API 30+;
     setUninstallBlocked(own package, true); addUserRestriction(DISALLOW_CONFIG_DATE_TIME);
     auto-time forced on (setAutoTimeEnabled on API 30+, otherwise
     setGlobalSetting(AUTO_TIME, "1")).
     OFF by default (constants in HardeningConfig, documented in KDoc): DISALLOW_FACTORY_RESET,
     DISALLOW_SAFE_BOOT, DISALLOW_DEBUGGING_FEATURES.
     Do NOT write any function that clears the device owner or removes these restrictions.
B. data/LockStateStore.kt: interface + DataStore implementation + pure codec:
   states: Flow<Map<String, LockState>>; snapshot(); lockUntilAtLeast(pkg, untilMillis) (result =
   max(existing, new) — I3); markUnlockedIfExpired(pkg, nowMillis): Boolean (the ONLY way to reach
   Unlocked — I2); ensureRecord(pkg) (creates Unlocked only if no record exists, never overwrites);
   forget(pkg) (no-op unless Unlocked). No other mutators.
C. lock/LockCoordinator.kt — the ONLY holder of AppFreezerApi (I1). Serialize everything with a
   Mutex. Public surface: reconcile(), onLimitReached(pkg, usedMinutes) (this class becomes
   LimiterApp.monitorCallbacks; onTick() calls reconcile()), tryEditConfig(ConfigEdit): EditResult,
   events: SharedFlow<LockEvent> (LimitLocked(pkg, until), WindowLocked(pkg, until),
   Unlocked(pkg)). There is NO unlock method.
   reconcile() algorithm, per target package (crash-safe ordering: on lock persist first, then
   freeze; on unlock unfreeze first, then persist):
     1. settings.rollover(now, zone, currently-locked packages).
     2. record == null: if isFrozen(pkg) -> lockUntilAtLeast(nextOccurrence(reveal)) (fail-safe),
        else ensureRecord.
     3. LockedUntil and now >= until: unfreeze; if Ok or NotDeviceOwner -> markUnlockedIfExpired and
        emit Unlocked; if Failed keep the lock (the next reconcile retries).
     4. LockedUntil and now < until: re-assert freeze if !isFrozen.
     5. Unlocked and inside [hideAt, revealAt) (handle windows crossing midnight): lock until
        nextOccurrence(reveal), freeze, emit WindowLocked.
     6. Unlocked but frozen: unfreeze (stored state is the authority).
     7. Records for packages that are no longer targets: forget() (only works when Unlocked).
     8. scheduler.rescheduleAll().
   onLimitReached: ignore non-targets and packages already locked (until > now); otherwise
   lock until nextOccurrence(reveal), freeze, history.recordLimitHit(epochDay, pkg), emit
   LimitLocked, rescheduleAll.
   tryEditConfig(Add/Update/Remove): reject with RejectedLocked(until) if the package is currently
   locked, RejectedProtected, or RejectedInvalid; otherwise delegate to SettingsRepository and
   return Accepted(appliedNow, deferred). After an accepted Add call ensureRecord and reconcile.
D. alarm/
   - TimeWindowScheduler(AlarmManager): rescheduleAll() is idempotent. It arms (1) one exact daily
     alarm per target at its next hideAt and (2) ONE reveal alarm at min(lockedUntil) + 1000 ms.
     Use setExactAndAllowWhileIdle(RTC_WAKEUP, ...). On API 31+ check canScheduleExactAlarms(); if
     false fall back to setAndAllowWhileIdle (the monitor tick reconciles as a safety net).
     PendingIntents: explicit component, distinct action + data Uri per alarm
     (limiter://hide/<pkg>, limiter://reveal), FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT.
   - AlarmReceiver (exported=false): goAsync(), run reconcile() on a coroutine, finish the pending
     result. Ignore hide alarms for packages that are not current targets.
   - SystemEventsReceiver (exported=false): BOOT_COMPLETED, MY_PACKAGE_REPLACED (update clears
     alarms), TIME_SET, TIMEZONE_CHANGED -> reconcile(), then ServiceStarter.ensureRunning.
     Do not use direct boot / LOCKED_BOOT_COMPLETED.
E. history/HistoryRepository.kt (DataStore + pure codec): firstRunEpochDay (set once) and per-day
   set of packages that hit their limit; recordLimitHit(epochDay, pkg); a Flow snapshot.
F. LimiterApp: wire LockCoordinator as monitorCallbacks; add `suspend fun bootstrap()` = apply
   HardeningPolicy (only if Device Owner) + set firstRun + reconcile() + ServiceStarter.ensureRunning.
G. Manifest: RECEIVE_BOOT_COMPLETED; USE_EXACT_ALARM; SCHEDULE_EXACT_ALARM with
   android:maxSdkVersion="32"; the two receivers with the actions above.

### Unit tests (required; fake AppFreezerApi, FakeTimeSource, in-memory LockStateStore)
limit -> locked until next reveal + freeze called; reconcile before expiry keeps the lock and
re-freezes an unfrozen app; at/after expiry unlocks and unfreezes; Failed unfreeze keeps the lock
then succeeds on retry; lockUntilAtLeast never shortens; no record + frozen -> fail-safe lock;
window lock when now is inside the window after a "missed" alarm; window crossing midnight;
locked package rejects Update/Remove via tryEditConfig; onLimitReached idempotent; pending change
for a locked package stays pending until unlocked; TimeMath edge cases.

### Deliverable
`./gradlew assembleDebug testDebugUnitTest` passes; STAGE3_NOTES.md listing LockCoordinator /
LockStateStore / SettingsRepository / HistoryRepository public interfaces.
```

---

## مرحله ۴ — رابط کاربری (Compose)

```text
You are building stage 4 of 5 for "Limiter". Read AGENTS.md, STAGE3_NOTES.md, LockCoordinator,
LockStateStore, SettingsRepository, UsageSnapshotStore and the manifest first.

### Scope of THIS stage
Compose UI + ViewModels only. Compose build config already exists: touch build.gradle.kts only to
add lifecycle-runtime-compose and lifecycle-viewmodel-compose (pinned).

A. MainActivity: setContent { LimiterTheme { AppRoot() } }; on start call app.bootstrap() in
   lifecycleScope. Navigation is a simple sealed `Screen` state held in a ViewModel (no
   navigation-compose).
B. Onboarding screen with a live checklist, refreshed on every ON_RESUME (the user returns from
   Settings): pure `OnboardingState.from(isDeviceOwner, usageAccess, notifications, exactAlarm)`.
   1. Device Owner: dpm.isDeviceOwnerApp. If not, show this exact command and these notes:
        adb shell dpm set-device-owner com.limiter.app/.admin.LimiterAdminReceiver
      All accounts on ALL users must be removed first (Settings -> Passwords & accounts; they can be
      re-added afterwards). Verify with: adb shell dumpsys account
      Do NOT use AccountManager to show an account count: on API 26+ an app cannot enumerate other
      apps' accounts, so the number would be misleading.
   2. Usage access -> Settings.ACTION_USAGE_ACCESS_SETTINGS.
   3. Notifications -> POST_NOTIFICATIONS runtime request (API 33+).
   4. Exact alarms -> status only. On API 31–32 when canScheduleExactAlarms() is false open
      ACTION_REQUEST_SCHEDULE_EXACT_ALARM; on 33+ it is granted through USE_EXACT_ALARM.
   5. Hardening status (read-only list of which HardeningConfig items are active).
   The target picker stays disabled until 1–2 are satisfied.
C. Home screen: one row per target from SettingsRepository + LockStateStore + UsageSnapshotStore
   (remaining minutes or "locked until <time>"); an "Add app" button.
D. Picker screen: launchable apps only (MAIN/LAUNCHER), excluding
   CriticalPackages/CriticalPackageResolver matches and already-selected apps. Adding uses defaults
   (limit 60 min, hide 23:00, reveal 07:00) via LockCoordinator.tryEditConfig(Add).
E. Per-app settings — structural branching (I4). `AppSettingsRoute(pkg)` first collects the
   package's LockState and branches at the top:
     when (state) { is LockedUntil -> LockedReadOnlyScreen(...); Unlocked -> EditableSettingsScreen(...) }
   EditableSettingsScreen is `private` to its file and reachable only from the Unlocked branch.
   The ViewModel's save/remove ALSO goes through tryEditConfig and shows the RejectedLocked result
   (defense in depth). Editable controls: limit stepper, hide/reveal time steppers (15-minute
   steps; no TimePicker dependency). When an edit is deferred, show "takes effect after midnight".
   LockedReadOnlyScreen shows the lock end time and a countdown. It has a Back button only.
F. Forbidden anywhere in the UI: unlock, skip, snooze, "emergency", debug menus, remove-while-locked.

### Unit tests (required, JVM only, ViewModels get interfaces via constructor — no Context)
OnboardingState derivation; SettingsViewModel.save rejected when locked; picker list excludes
protected and already-selected packages; deferred-edit message logic.

### Deliverable
`./gradlew assembleDebug testDebugUnitTest` passes; STAGE4_NOTES.md.
```

---

## مرحله ۵ — فیچرهای جانبی + امضا/Release + مستندات

```text
You are building stage 5 of 5 (final) for "Limiter". Read AGENTS.md, STAGE2_NOTES.md,
STAGE3_NOTES.md, STAGE4_NOTES.md, UsageMonitorService, LockCoordinator (events flow),
HistoryRepository, the current workflow file and build.gradle.kts first.

### Scope of THIS stage
A. Grace notifications: pure GracePlanner(remainingMinutes, alreadySent) -> which level to send
   (10 min or 2 min, once per app per local day; if the remaining time jumps below both, send only
   the most urgent unsent level). Sent-state persisted in DataStore. Called from the monitor tick.
   A separate notification channel (default importance).
B. Reflection log: on LockEvent.LimitLocked post a notification with three FIXED action buttons
   ("Worth it", "Neutral", "Wasted time"). No free-text RemoteInput. A ReflectionReceiver
   (exported=false, immutable PendingIntents) stores (timestamp, pkg, choice) in
   ReflectionLogRepository (DataStore + pure codec, newest first, capped at 500). Add a read-only
   list screen. Register the event collector in LimiterApp.onCreate (no I/O there). These actions
   must not touch lock state.
C. Streak calendar: pure StreakCalculator (a day is "clean" if no limit hit was recorded between
   firstRunEpochDay and today; current streak and best streak) + a read-only month-grid Compose
   screen. Add Screen.History / Screen.Reflections to the stage-4 navigation state.
D. Widget: classic AppWidgetProvider + RemoteViews (NOT Glance). widget_info XML with
   updatePeriodMillis="0"; the monitor service pushes updates after each tick through a
   WidgetUpdater. Pure WidgetText.format(snapshot) -> "23m · <app label>", "Locked until 07:00" or
   "—". Tapping opens MainActivity (immutable PendingIntent).
E. Signing + CI. In app/build.gradle.kts define signingConfigs.release ONLY when the environment
   variable LIMITER_KEYSTORE_PATH is set (also read LIMITER_KEYSTORE_PASSWORD, LIMITER_KEY_ALIAS,
   LIMITER_KEY_PASSWORD). buildTypes.release: isMinifyEnabled = false, isDebuggable = false,
   signingConfig = the release config if present, otherwise the debug config, so local builds never
   fail. Never commit a keystore. Update .github/workflows/build.yml: keep the verification job; add
   a release step that runs only when the repository secret LIMITER_KEYSTORE_BASE64 exists (secrets
   cannot be used directly in `if`, so map it to an env var such as HAS_KEYSTORE first): decode it to
   $RUNNER_TEMP/limiter.jks, export the four env vars from the secrets LIMITER_KEYSTORE_PASSWORD,
   LIMITER_KEY_ALIAS, LIMITER_KEY_PASSWORD, run `./gradlew assembleRelease`, upload the release APK
   as an artifact. Pin JDK 17 and the action versions from stage 1. You cannot run the workflow:
   validate YAML syntax only.
F. FINAL_README.md, covering:
   - Creating the keystore once (keytool -genkeypair -v -keystore limiter.jks -alias limiter
     -keyalg RSA -keysize 2048 -validity 10000), base64-encoding it for the secret, and building
     release locally with the same env vars. Local and CI APKs must use the SAME key so they can
     update each other (a Device Owner app cannot simply be uninstalled).
   - Device Owner setup: remove all accounts on all users, verify with `adb shell dumpsys account`,
     run `adb shell dpm set-device-owner com.limiter.app/.admin.LimiterAdminReceiver`, re-add
     accounts, then grant usage access and notifications.
   - Updating: `adb install -r` with the same signing key.
   - What each HardeningConfig flag does, and the trade-off of DISALLOW_DEBUGGING_FEATURES (it
     disables adb, which you need to update/remove the app).
   - Recovery, stated honestly: there is no in-app unlock by design. With strict flags on, the only
     way out is a factory reset through recovery/bootloader. Test on an emulator or spare phone
     first.
   - First-run checklist.
G. FINAL_NOTES.md summarizing the whole build (notes format from AGENTS.md).

### Unit tests (required)
GracePlanner, StreakCalculator, reflection-log cap and codec, WidgetText.format.

### Constraints
No lock-bypass mechanism of any kind (I1–I7). No changes to lock semantics.

### Deliverable
`./gradlew assembleDebug testDebugUnitTest` passes locally; workflow YAML validated; FINAL_README.md
and FINAL_NOTES.md written.
```
