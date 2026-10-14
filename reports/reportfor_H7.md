# Task H7-Final Report: OS Clock-Tamper Lock, Horizon Clamp & ADB Recovery

## 1. Summary of Changes
- **OS Date/Time Lock (DPM)**: Added `enforceTimeTamperProtection()` in `DpmGateway` and overridden in `DeviceDpmGateway`. Invokes `setAutoTimeEnabled(true)`, `setAutoTimeZoneEnabled(true)`, and `addUserRestriction(DISALLOW_CONFIG_DATE_TIME)`. Invoked in `LockCoordinator.enforceSelfProtection()`.
- **Horizon Clamp**: Introduced `MAX_LOCK_HORIZON_MILLIS = 36h` in `LockCoordinator`. In `reconcile()`, any stored lock deadline beyond `now + 36h` is clamped to today's reveal time (if inside window) or `now` (immediate unlock/recovery). In `onLimitReached()`, existing valid future locks are not pushed further unless a stricter window rule requires it.
- **Future Usage Timestamp Filtering**: In `UsageCalculator.foregroundMillis` and `UsageStatsReader.queryEvents`, ignored any event with timestamp > now (`toMillis`/`endMillis`).
- **Broadcasts**: Verified `ACTION_TIME_CHANGED` and `ACTION_TIMEZONE_CHANGED` handled in `SystemEventsReceiver` with `reconcile()` and `reschedule()`.

## 2. Test & Build Status
- `testDebugUnitTest`: 291 passed, 0 failed (offline JVM).
  - Verified `enforceSelfProtection()` invokes `dpmGateway.enforceTimeTamperProtection()`.
  - Verified 5-day stranded lock is clamped and unlocked immediately in `reconcile()`.
  - Verified future-timestamped usage events are ignored in `UsageCalculator`.
- `assembleDebug`: Built successfully (`app-debug.apk`).

## 3. On-Device Verification (Xiaomi Redmi 12 - `4a952d897d79`)
- Backed up DataStore: `pre_h7_lock_state_1791990028.pb` and `pre_h7_settings_1791990028.pb`.
- Executed one-time reset of stranded test lock state.
- Installed APK and started `MainActivity`.
- **Device Owner**: Active (`com.limiter.app/.admin.LimiterAdminReceiver`).
- **User Restriction**: `no_config_date_time` confirmed active via `dumpsys user`.
- **Target App Physical States**:
  - `ir.nasim`, `org.chromium.webapk.a5b83bbe72c293c31_v2`, `ir.ilmili.telegraph`, `com.dv.adm`: Unlocked/unhidden (`hidden=false suspended=false`).
  - `net.iGap`, `com.supercell.clashroyale`: Active legitimate locks clamped to tonight (`hidden=true suspended=true`, deadline <= 36h).
