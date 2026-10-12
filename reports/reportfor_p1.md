# Phase P1 Report: Entry Points Security & Robustness (H1, H5, H6)

- **Status:** COMPLETED
- **Completed:**
  - **H1:** Verified `MainActivity.kt` rejects all mutation intent extras via `IntentGateDecision.REJECT_MUTATION`. Cleaned unused import. Verified `AndroidManifest.xml` export declarations (`MainActivity`: exported, launcher only; `LimiterAdminReceiver`: exported with `BIND_DEVICE_ADMIN`; `UsageMonitorService` & `AlarmReceiver`: not exported; `SystemEventsReceiver`: exported for boot/time events).
  - **H5:** Hardened `AlarmReceiver` and `SystemEventsReceiver` with `withTimeout(8_000L)`, atomic single-execution `finishOnce()`, and `try-finally { finishOnce() }` guarantees. Added safe intent action access to avoid unmocked JVM stub crashes. Added `onFinish` test hooks.
  - **H6:** Protected `UsageMonitorService.start()` against `ForegroundServiceStartNotAllowedException` and generic exceptions in `LimiterApp.onCreate`, `MainActivity.onStart`, `AlarmReceiver`, and `SystemEventsReceiver`. Hardened `monitorLoop()` with structured exception handling and exponential backoff (`calculateLoopBackoff(failures)`: 5s, 10s, 30s, 60s) while rethrowing coroutine `CancellationException`.
  - **Tests:** Added 7 unit tests covering receiver completion on success/exception/timeout and monitor loop backoff calculation.
- **Evidence:**
  - COMPILED: `./gradlew assembleDebug --offline` exited with code 0 (BUILD SUCCESSFUL in 3s).
  - TESTED (JVM): `./gradlew testDebugUnitTest --offline` exited with code 0. 238 tests passed / 0 failed (total tests increased: 199 → 238).
  - VERIFIED ON DEVICE: NOT RUN (P1 specifies no phone changes in this phase).
- **Commits:** `459101c` — P1: security and robustness of entry points (H1, H5, H6)
- **Blockers:** None.
- **Next:** Phase P2: Exact Alarm Permission & AppOps (H3)
