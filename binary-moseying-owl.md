# Plan: Write comprehensive code-review report for Limiter

## Context
User asked for a precise, complete report (Persian) of all bugs/issues in the Limiter Android project after re-reading the updated files, delivered as a single .md file. Re-read confirms file sizes unchanged vs. first pass (LockCoordinator 565, LimiterScreen 1204, MainViewModel 364 lines...), so earlier findings still hold; new findings from UI/data/MainActivity were added. Nothing was built/run (no keystore, not a git repo).

## Report structure
1. Summary + severity table (counts per severity), scope/method/limitations (not built, tests not run).
2. Critical/High
   - H1 `MainActivity.handleIntent`: exported activity accepts `add_target/limit/hide/reveal` extras -> any app/adb can add targets; bypasses self-lock gate (MainActivity.kt:47-61).
   - H2 Self-lock enforced only in ViewModel; `LockCoordinator.tryEditConfig/tryRemoveConfig` unguarded. `_currentMillis` only ticks in SelfLockScreen/onResume, so selfLockStatus is stale on dashboard when a window starts (MainViewModel.kt:99-110, 224/303/327). SelfLockScreen uses `System.currentTimeMillis()` not `TimeSource`.
   - H3 `onTick()` never called; service never reconciles; retry/unlock depends only on alarms (LockCoordinator.kt:97, UsageMonitorService).
   - H4 TimeWindowScheduler: no alarm for `ReleasePending`; `REQUEST_RETRY` never scheduled; hide alarms indexed by map order, never cancelled.
   - H5 Receivers launch coroutines without `goAsync()` (AlarmReceiver, SystemEventsReceiver).
   - H6 `startForegroundService` unguarded (Application.onCreate, MainActivity.onStart, receivers) -> ForegroundServiceStartNotAllowedException risk; `monitorLoop` has no try/catch -> silent death.
   - H7 Clock tampering: wall-clock locks; no `setAutoTimeEnabled`/DISALLOW_CONFIG_DATE_TIME; Usage Access revocable; no UI/intent to grant permissions (only ADB).
3. Medium
   - Phase E pending changes for uninstalled packages never applied/cleared (LockCoordinator ~l.215).
   - `freezer.freeze()` result ignored everywhere (lock recorded even if freeze failed).
   - `recordUnlockFailure` does `?: return@edit` when no record -> Phase G "history not lost" claim false for null-record packages (DataStoreLockStateStore.kt:142).
   - UsageStatsReader queries 48h events per target per tick, on Main thread (lifecycleScope Main.immediate); service calls `reschedule()` every tick per over-limit target.
   - MainViewModel recomputes `resolveProtected()` (heavy IPC) on every combine emission (each second in lock screen), on Main; `checkStatus()` in init on Main.
   - `UsageSnapshotStore.decodeSnapshots` uses `require` -> corrupt line crashes the UI flow (Codec elsewhere is tolerant, inconsistent).
   - DeviceCriticalPackageResolver: NPE risk `it.activityInfo.packageName`; `isEligibleTarget` excludes all FLAG_SYSTEM (YouTube/Chrome on Xiaomi not targetable); protects all enabled IMEs / a11y services / GMS.
   - Non-atomic `setTargets` + `setPending` in Phase E; read-modify-write across stores.
   - `isPackageInstalled` returns true on any unexpected exception (can keep stale state).
4. Build/config
   - gradle.properties: hard-coded `org.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64` (breaks macOS/CI), private proxy 10.97.201.106:8080.
   - minSdk 35 > compileSdk 34 (+ targetSdk 34); check-environment.sh checks android-34 only.
   - `proguard-rules.pro` missing; `AGENTS.md` referenced everywhere but absent; keystore.properties required even for tests (CI generates dummy).
   - `lint abortOnError=false`, `isMinifyEnabled=false` for release; `device_admin.xml` empty uses-policies with "Stage 1" comment; `android:testOnly` only in debug (good).
   - Dead SDK_INT >= TIRAMISU/S branches with minSdk 35.
5. Low / code quality
   - Dead code: `onTick`, `AppFreezer.unfreeze` (doc contradicts behavior), `getAllFrozenPackages`, `ACTION_RETRY`, `ensureRecord`, `markUnlockedIfExpired`, `recordReleasePending` (verify usage), unused imports (`Uri`).
   - `limitReachedCallback` var with getter; `lockCoordinator: LockCoordinator?` nullable though always non-null; fully-qualified names repeated in LockCoordinator; duplicated `isInsideWindow`; 4x duplicated unfreeze/failure block; stale "Stage N"/"§N (approx)" comments; hardcoded English strings in ViewModel feedback (fa strings exist); `catch (_: Exception) {}` swallowing in MainActivity.
   - Custom pipe/tilde text codecs instead of JSON despite comments saying JSON.
6. Docs/tests
   - README says 164 tests; actual `@Test` count = 188. README says "Android 15 only / public use" contradictory; FINAL_README vs README duplication; README "no force-stop" claim vs MIUI behavior.
   - No tests for service, scheduler, DeviceDpmGateway, receivers, MainActivity intent path.
7. Positives (brief): clean layering, DPM behind interface + fakes, mutex-serialized coordinator, monotonic lock-until, no INTERNET permission, allowBackup=false, AlarmReceiver not exported.
8. Authorship assessment: evidence (AGENTS.md §, Stage/Requirement/R-numbers, hotfix comments, sandbox Linux paths/proxy, FINAL_README, emoji README) -> agentic coding tool in a Linux sandbox (Codex/Jules-like); low confidence, no definitive marker.
9. Prioritized fix roadmap (ordered list) and suggested verification steps (build with fixed gradle props, run `./gradlew testDebugUnitTest`, on-device checks: adb intent injection test, time-change test, reboot test).

## Verification
After writing: re-open the file to check Markdown renders, every file:line reference exists (spot-check with grep), and counts (188 tests) are correct.
