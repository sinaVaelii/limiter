# Report P0 — Triage and Root Cause Analysis
Commit before/after: fe4c677 → 4130016

## Done (ID → what, file:line)
- All §4 IDs audited with file:line evidence in `reports/triage.md`.
- H7: Documented DO time policy and monotonic elapsed-time options (`reports/triage.md:43-47`).
- Deadline question: `phone epoch (1791839063) < stored deadline (1792244640)`. Root cause identified via `dumpsys alarm` rtc logs showing prior test execution under `2026-10-17 18:35:44` (`reports/triage.md:49-57`).
- Cases a-f verified against `TimeMath.nextOccurrence` & `SelfLockPolicy.isInsideWindow` (`reports/triage.md:59-71`).
- Delayed 17:17:57 HIDE alarm root cause identified via inexact alarm batching (`reports/triage.md:73-77`).
- `STATE.md` created tracking decisions D1-D5, open issues, and phone status.

## Verified (COMPILED | TESTED (JVM) | VERIFIED ON DEVICE | NOT RUN) + new test names + counts (total tests before → after)
- VERIFIED ON DEVICE: `adb shell date` (`Tue Oct 13 00:34:23 +0330 2026`), `date +%s` (`1791839063`), `dumpsys alarm` (u0a379 alarm counts & rtc history).
- TESTED (JVM): Target suite `./gradlew testDebugUnitTest --offline --tests "*TimeMathTest*"` passed (15/15 tests, total 199 → 199).
- COMPILED: Baseline project compiled cleanly without mutations.

## Not done / blocked / needs my decision
- No code modifications in this phase (P0 is strictly read-only triage).
- Decision D4: Review options for H7 time tampering policy before Phase P3/P7.

## Changed files (git diff --stat, one-line reason each)
- `reports/triage.md` — Complete §4 audit table, H7 options, deadline and alarm analysis.
- `STATE.md` — State tracker for decisions, phases, open bugs, and device status.
- `reports/reportfor_p0.md` — Phase P0 summary report.

## Risks / things I should test on the phone
- `com.dv.adm` remains locked on the phone until `1792244640000` (2026-10-17 17:14) due to monotonic lock preservation from earlier clock manipulation.
- Do NOT launch `MainActivity` on the phone yet; in P2/P3 normal reconcile will legitimately handle expired state.
