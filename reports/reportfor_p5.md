# Report for Phase P5: Performance and Tolerance (M4, M5, M6, M7)

## 1. Objectives & Summary
Completed Phase P5 targeting performance, background scheduling efficiency, crash resilience, and system app eligibility:
- **M4:** Backgrounded `UsageStatsReader` queries via `Dispatchers.IO` and narrowed lookback window from 48h to 3h. Added `SchedulePlan` caching to `TimeWindowScheduler` with `rescheduleIfPlanChanged()` to prevent redundant `AlarmManager` IPCs during monitor service polling.
- **M5:** Implemented thread-safe caching of `resolveProtected()` in `DeviceCriticalPackageResolver`, invalidated on package broadcasts and app resume. Moved `checkStatus()` in `MainViewModel` to background coroutines off the Main thread.
- **M6:** Made `UsageSnapshotStore.decodeSnapshots` tolerant of corrupt payload lines, logging warnings via `safeLogW` and preserving all valid records.
- **M7:** Fixed NPE risk on `activityInfo?.packageName` in `DeviceCriticalPackageResolver`. Added "Show system apps" toggle with confirmation dialog in `AppPickerDialog`, permitting launchable non-protected system apps (e.g. YouTube/Chrome) while preserving strict immunity for core system apps and Limiter.

## 2. Evidence of Implementation & Verification
- **Unit Tests:** Added `PerformanceAndToleranceP5Test.kt` verifying M4 lookback/plan caching, M5 resolver caching/invalidation, M6 corrupt snapshot line tolerance, and M7 system apps filtering and protected immunity.
- **Test Command:** `./gradlew testDebugUnitTest --offline` -> Passed (275 tests).
- **Build Command:** `./gradlew assembleDebug --offline` -> Passed (Exit code 0).

## 3. Invariants & Stability
- Codec formats, DataStore contracts, and package isolation rules fully preserved.
- No DPM restrictions removed; supported debug recovery path remains intact.
