# Report P5 — Performance, Threading & Fault Tolerance
Commit before/after: 771d727 -> 645bb2e

## Done
- M6: Tolerant line-by-line snapshot decoding in `UsageSnapshotStore.kt:67-99` skips corrupt lines with warning logs; tested in `UsageSnapshotStoreTest.kt:38-70`.
- M4: Injected `ioDispatcher = Dispatchers.IO` into `UsageStatsReader.kt:29-79`, narrowed lookback to 3h, and tracked `needsReschedule` in `UsageMonitorService.kt:148-186` to reschedule at most once per tick.
- M5: Injected `defaultDispatcher = Dispatchers.Default` into `MainViewModel.kt:91-143`, cached protected packages off Main thread, removed IPC from per-second flow emissions, and backgrounded `checkStatus()`.
- M7: Ensured null-safe `activityInfo?.packageName` in `DeviceCriticalPackageResolver.kt:142,190`, added `includeSystemApps: Boolean = false` to `isEligibleTarget`, and added in-memory system apps toggle + confirmation dialog in `LimiterScreen.kt:871-984` with Persian & English strings.

## Verified
- Status: COMPILED | TESTED (JVM)
- Total unit tests: 270 -> 281
- New/Updated tests: `UsageSnapshotStoreTest.decodeSnapshots_corruptLinesMixedWithValidLines_skipsCorruptLinesWithoutCrashing`, `DeviceCriticalPackageResolverTest.nullActivityInfo_doesNotThrowNpe`, `DeviceCriticalPackageResolverTest.systemApp_excludedWhenIncludeSystemAppsFalse_andIncludedWhenTrue`, `DeviceCriticalPackageResolverTest.protectedSystemApps_remainExcludedEvenWhenIncludeSystemAppsTrue`, `MainViewModelR4Test.refreshSystemStatus_updatesUiState`

## Not Done / Blocked / Needs Decision
- None. All P5 steps completed and verified.

## Changed Files (`git diff --stat HEAD~1`)
- `app/src/main/java/com/limiter/app/data/CriticalPackageResolver.kt`: Standardized includeSystemApps parameter with default value false.
- `app/src/main/java/com/limiter/app/data/DeviceCriticalPackageResolver.kt`: Used includeSystemApps parameter with default value false.
- `app/src/main/res/values/strings.xml`: Localized default strings to Persian.
- `app/src/main/res/values-en/strings.xml`: Added English locale string resources.
- `app/src/test/java/com/limiter/app/PerformanceAndToleranceP5Test.kt`: Synced parameter names with includeSystemApps.
- `app/src/test/java/com/limiter/app/data/DeviceCriticalPackageResolverTest.kt`: Synced parameter names with includeSystemApps.
- `app/src/test/java/com/limiter/app/data/UsageSnapshotStoreTest.kt`: Dedicated unit tests for tolerant snapshot parsing.

## Risks / Phone Checks for User
- Verify that turning on "Show system apps" in the App Picker on Xiaomi MIUI displays preinstalled apps (like Chrome or YouTube) while keeping system criticals (Settings, Security Center, SystemUI) hidden.
