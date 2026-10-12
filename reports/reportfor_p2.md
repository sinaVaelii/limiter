# Phase P2 Report: Time Windows and Alarms (Deadline bug, H4, Exact Alarm)

- **Status:** COMPLETED
- **Completed:**
  - **Deadline bug & Cases a–f:** Verified `TimeMath.nextOccurrence` and `SelfLockPolicy` in `Asia/Tehran` for all cases a–f (`lockUntil - now <= 24h` invariant holds). Added regression test `deadlineCases_inTehranTimezone_conformToExpectedBounds`. Added regression test `expiredLock_reconcilesToUnlockedAndUnfreezes` confirming expired lock on `com.dv.adm` unlocks and unfreezes when epoch >= deadline.
  - **H4 (TimeWindowScheduler):**
    - (a) `calculateEarliestUnlockPlan` evaluates `ReleasePending` records (wakes at `now` or retry time).
    - (b) Evaluates `nextUnlockRetryAtMillis` across all states (including `LockedUntil`) and schedules `ACTION_RETRY` (`REQUEST_RETRY`).
    - (c) Verified hide request codes are deterministic (`calculateHideRequestCode`) and distinct across packages.
    - (d) `reschedule()` detects removed targets and cancels their alarms; detects changed hide windows, explicitly cancels the old alarm, and schedules the replacement.
  - **Exact Alarm UI:** Added button in `SystemStatusCard` in `LimiterScreen.kt` when `!state.hasExactAlarmPermission` that opens `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` with `package:${context.packageName}`. Added bilingual strings (`btn_grant_exact_alarm` in fa/en). Re-checked on resume via `onResume -> refreshSystemStatus()`.
  - **Phone (Step 4):** Granted exact alarm on test device: `adb shell appops set com.limiter.app SCHEDULE_EXACT_ALARM allow`; verified via `adb shell appops get com.limiter.app SCHEDULE_EXACT_ALARM` returning `SCHEDULE_EXACT_ALARM: allow`.
  - **Tests:** Added 5 unit tests covering deadline conformance, expired lock unfreeze, retry plan selection, distinct request codes, and removed/changed target alarm cancellation.
- **Evidence:**
  - COMPILED: `./gradlew assembleDebug --offline` exited with code 0 (BUILD SUCCESSFUL in 1s).
  - TESTED (JVM): `./gradlew testDebugUnitTest --offline` exited with code 0. 243 tests passed / 0 failed (total tests increased: 238 → 243).
  - VERIFIED ON DEVICE: `adb shell appops get com.limiter.app SCHEDULE_EXACT_ALARM` -> `SCHEDULE_EXACT_ALARM: allow`.
- **Commits:** `1c79141` — P2: time windows, alarms, and exact alarm permission (H4)
- **Blockers:** None.
- **Next:** Phase P3: Lock/unlock correctness (H3, M1, M2, M3, M8, M9, fallback)
