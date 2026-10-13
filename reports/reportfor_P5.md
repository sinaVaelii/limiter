# Report P5 — Performance, Threading & Fault Tolerance
Commit before/after: 0cd9c5d -> ab279bd

## Done
- M6: Tolerant line-by-line snapshot decoding in `UsageSnapshotStore.kt:67-99` skips corrupt lines without crashing.
- M4: Added `ioDispatcher` to `UsageStatsReader.kt:25-78` offloading queries, narrowed lookback window to 3h, tracked `needsReschedule` in `UsageMonitorService.kt:148-186` to reschedule at most once per tick.
- M5: Added `defaultDispatcher` to `MainViewModel.kt:89-137` and cached protected package resolution off Main thread, removing IPC from flow emissions.
- M7: Ensured null-safe `activityInfo?.packageName` in `DeviceCriticalPackageResolver.kt:142,190`, permitted non-protected system apps when toggled, and added in-memory `hasConfirmedSystemAppsDialog` guard in `LimiterScreen.kt:870-885,970-985`.

## Verified
- Status: COMPILED | TESTED (JVM)
- Total unit tests: 270 -> 278
- New/Updated tests: `DeviceCriticalPackageResolverTest.nullActivityInfo_doesNotThrowNpe`, `DeviceCriticalPackageResolverTest.systemApp_excludedWhenIncludeSystemAppsFalse_andIncludedWhenTrue`, `DeviceCriticalPackageResolverTest.protectedSystemApps_remainExcludedEvenWhenIncludeSystemAppsTrue`, `MainViewModelR4Test.refreshSystemStatus_updatesUiState`

## Not Done / Blocked / Needs Decision
- None. All P5 steps completed and verified.

## Changed Files (`git diff --stat HEAD~1`)
- `app/src/main/java/com/limiter/app/MainActivity.kt`: Reverted premature enableEdgeToEdge call.
- `app/src/main/java/com/limiter/app/data/UsageStatsReader.kt`: Added ioDispatcher parameter with default Dispatchers.IO.
- `app/src/main/java/com/limiter/app/service/UsageMonitorService.kt`: Tracked needsReschedule to call scheduler at most once per tick.
- `app/src/main/java/com/limiter/app/ui/LimiterScreen.kt`: Added in-memory hasConfirmedSystemAppsDialog state.
- `app/src/main/java/com/limiter/app/ui/MainViewModel.kt`: Added defaultDispatcher parameter and backgrounded status/protected packages refresh.
- `app/src/test/java/com/limiter/app/ui/MainViewModelR4Test.kt`: Injected testDispatcher into defaultDispatcher and added advanceUntilIdle.
- `app/src/test/java/com/limiter/app/data/DeviceCriticalPackageResolverTest.kt`: Dedicated unit tests for null safety and system app filtering.

## Risks / Phone Checks for User
- Verify that turning on "Show system apps" in the App Picker on Xiaomi MIUI displays preinstalled apps (like Chrome or YouTube) while keeping system criticals (Settings, Security Center, SystemUI) hidden.
