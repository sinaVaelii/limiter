# Report P4 — Product rules for limits and self-lock (D5)
Commit before/after: 26bf1db → 28f79cd

## Done (ID → what, file:line)
- D5/Rule A: Daily limit range [1, 720] min, max raise +30 min, cumulative cap against active limit (SettingsPolicy.kt:28).
- D5/Rule A: Auto-clamping of limits > 720 to 720 on reconcile + UsageMonitorService (LockCoordinator.kt:180, UsageMonitorService.kt:140).
- D5/Rule B: Pure SelfLockGate allowing dashboard, app picker, adding targets, limit decrease; blocking removals/loosening (SelfLockPolicy.kt:124).
- D5/Rule B: LockCoordinator.tryEditConfig & tryRemoveConfig enforce SelfLockGate directly within mutex (LockCoordinator.kt:613, 674).
- D5/H2: Removed System.currentTimeMillis from SelfLockScreen; driven by TimeSource ticker; added dashboard & add-app actions (LimiterScreen.kt:390, 800).
- D5/UI: Picker displays already limited apps disabled with localized badge; batch-add confirmation dialog under self-lock (LimiterScreen.kt:960, 1020).
- Strings: Added and updated Persian & English resources for all P4 errors and confirmation messages (strings.xml, values-fa/strings.xml).

## Verified (COMPILED | TESTED (JVM) | VERIFIED ON DEVICE | NOT RUN)
- COMPILED: assembleDebug PASS (exit code 0).
- TESTED (JVM): testDebugUnitTest PASS (exit code 0, 249 → 268 passing tests).
  - New test suite: ProductRulesP4Test (19 unit tests covering 1..720 range, +30 cap, cumulative active limit check, immediate decrease, 1000->720 clamp, SelfLockGate allow/block actions, coordinator gate, and TimeSource boundary).
  - Updated existing tests: SettingsPolicyTest (looser +30), AppPickerAndConfigTest (720 max and +30).
- VERIFIED ON DEVICE: NOT RUN (device integration pending human verification).

## Not done / blocked / needs my decision
- None for P4. Ready for P5 (Performance / Background work / Memory).

## Changed files (git diff --stat, one-line reason each)
- app/src/main/java/com/limiter/app/policy/SettingsPolicy.kt: Added 720/30 limit constants, validation, window loosening helper.
- app/src/main/java/com/limiter/app/policy/SelfLockPolicy.kt: Added SelfLockAction and SelfLockGate pure authorization rules.
- app/src/main/java/com/limiter/app/policy/LockCoordinator.kt: Added reconcile limit clamping and SelfLockGate enforcement.
- app/src/main/java/com/limiter/app/service/UsageMonitorService.kt: Clamped effective daily limit minutes to 720.
- app/src/main/java/com/limiter/app/ui/LimiterScreen.kt: SelfLockScreen TimeSource ticker, dashboard banner, picker & dialog self-lock rules.
- app/src/main/java/com/limiter/app/ui/MainViewModel.kt: Updated selection and submission logic to respect SelfLockGate.
- app/src/main/res/values/strings.xml & values-fa/strings.xml: Added Persian/English resources for P4 strings.
- app/src/test/java/com/limiter/app/policy/ProductRulesP4Test.kt: 19 new comprehensive tests for Rule A & Rule B.
- app/src/test/java/com/limiter/app/policy/SettingsPolicyTest.kt: Updated loosening test to +30 min limit.
- app/src/test/java/com/limiter/app/ui/AppPickerAndConfigTest.kt: Updated daily limit range and loosening tests.
- STATE.md: Updated phase tracker to P4 completed.

## Risks / things I should test on the phone
- Verify on phone that opening Limiter during an active self-lock window allows opening app picker and adding new targets.
- Confirm that existing limited targets cannot be deleted or loosened while self-locked.
- Confirm that Telegraph with 1000m limit clamps to 720m upon background reconcile without crashing.
