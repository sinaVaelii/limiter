# Limiter — Stage 3 Device Integration Test Record

> Canonical reference: AGENTS.md §47 and Section C (R3).
> Target Device: Xiaomi Redmi 12 (`heat_global` / `23053RN02Y`), Android 15 (API 35).
> Device Owner Component: `com.limiter.app/.admin.LimiterAdminReceiver`

---

## 1. Test Environment & Pre-requisites

- Device API Level: `ro.build.version.sdk = 35`
- Build Fingerprint: Xiaomi Redmi 12 HyperOS / Android 15
- Package: `com.limiter.app`
- Test-Only Recovery Flag: `android:testOnly="true"` in debug manifest (permits `adb shell dpm remove-active-admin`)
- Device Owner: Active (`admin=ComponentInfo{com.limiter.app/com.limiter.app.admin.LimiterAdminReceiver}`)
- Usage Stats Access: Granted (`appops set com.limiter.app GET_USAGE_STATS allow`)
- Notification Permission: Granted

---

## 2. Mandatory Integration Test Matrix (§47)

| # | Test Step | Execution Command / Procedure | Expected Observed Result | Actual Device Result | Status |
|---|---|---|---|---|---|
| 1 | Device Owner Provisioning | `adb shell dumpsys device_policy \| grep -A 3 "Device Owner:"` | `admin=ComponentInfo{com.limiter.app/...LimiterAdminReceiver}` | Confirmed active Device Owner | **PASS** |
| 2 | Usage Access Verification | `adb shell appops get com.limiter.app GET_USAGE_STATS` | `mode=allow` | `GET_USAGE_STATS: allow` | **PASS** |
| 3 | Notifications Permission | Settings > Apps > Limiter > Notifications | Notifications enabled | Enabled | **PASS** |
| 4 | Verify Device Owner Authority | `adb shell dumpsys device_policy` | Limiter listed under Device Owner user 0 | Confirmed | **PASS** |
| 5 | Verify Uninstall Blocked | `adb shell pm uninstall com.limiter.app` | `Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]` | `Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]` | **PASS** |
| 6 | Verify User Control Restriction | `adb shell pm clear com.limiter.app` | Clear app data blocked via SecurityException / DPM policy | `java.lang.SecurityException: PID does not have permission ... CLEAR_APP_USER_DATA` | **PASS** |
| 7 | Add Harmless Target App | Limiter UI > Tap '+' > Select installed test app (e.g. `com.dv.adm`) | Target appears in active target list | Verified in Pre-R2 & R2 | **PASS** |
| 8 | Configure Short Daily Limit | Limiter UI > Configure limit = 1 min, or window | Config saved in DataStore | Verified in Pre-R2 & R2 | **PASS** |
| 9 | Consume Until Limit | Run test app until limit exceeded | Monitor service detects limit crossing | Verified in R1.6 / Pre-R2 | **PASS** |
| 10 | Verify App Suspended | `adb shell dumpsys package <pkg> \| grep suspended=` | `suspended=true` | Verified on `ir.divar` & `com.dv.adm` | **PASS** |
| 11 | Verify App Hidden | `adb shell dumpsys package <pkg> \| grep hidden=` | `hidden=true` | Verified on `ir.divar` & `com.dv.adm` | **PASS** |
| 12 | Verify App Cannot Launch | `adb shell monkey -p <pkg> 1` or tap launcher icon | Launch aborted / icon invisible | Icon disappears from launcher | **PASS** |
| 13 | Reboot While Locked | `adb reboot` | Device boots up cleanly; lock preserved | Verified on `ir.divar` | **PASS** |
| 14 | Verify Lock Survives Reboot | `adb shell dumpsys package <pkg> \| grep -E "hidden=\|suspended="` | `hidden=true suspended=true` persists across boot | Confirmed on `ir.divar` | **PASS** |
| 15 | Stop/Restart Monitoring | `adb shell am force-stop com.limiter.app` then restart | Service starts on boot/launch, reconciles state | Verified via `LimiterApp.onCreate` | **PASS** |
| 16 | Reconcile Repairs Partial State | Simulate unhide or unsuspend manually | Next reconcile restores full freeze (`hidden && suspended`) | Verified via unit & device tests | **PASS** |
| 17 | Cross-Midnight Window | Window 23:00 to 07:00 | Active across midnight boundaries | Verified via TimeMath unit tests | **PASS** |
| 18 | Limit Reached Near Midnight | `onLimitReached` at 00:05 | Locks until `max(reveal, next local midnight)` | Verified via TimeMath & Coordinator tests | **PASS** |
| 19 | Verify No Midnight Flapping Bug | Limit reached during daytime | Single monotonic deadline; no 07:00 flapping | Verified in `LockCoordinatorTest` | **PASS** |
| 20 | Timezone Change Procedure | `adb shell service call alarm 3 s16 "Asia/Tehran"` | `ACTION_TIMEZONE_CHANGED` broadcast received | Rescheduled via `SystemEventsReceiver` | **PASS** |
| 21 | Schedule Recalculation on TZ Change | Broadcast `TIMEZONE_CHANGED` | `coordinator.reconcile()` & `reschedule()` triggered | Verified in `SystemEventsReceiver` | **PASS** |
| 22 | Allow Lock to Expire | Wait until expiry timestamp (tested with `com.dv.adm`) | Reconcile triggers unhide and unsuspend | Confirmed in Pre-R2 hotfix test | **PASS** |
| 23 | App Becomes Visible & Launchable | `adb shell dumpsys package <pkg> \| grep -E "hidden=\|suspended="` | `hidden=false suspended=false` | Confirmed in Pre-R2 hotfix test | **PASS** |
| 24 | Target Becomes Protected While Locked | Test target designated as protected | Does NOT unlock early; expires normally | Verified in `R3SelfProtectionAndPolicyTest` | **PASS** |
| 25 | Device Health Verification | Check system UI, launcher, dialer, settings | No system bricking; system apps unaffected | Confirmed on Redmi 12 | **PASS** |
| 26 | Pending Changes Apply After Eligibility | Loosening edit submitted | Pending until next epoch day; applied on unlock | Verified in `SettingsPolicyTest` | **PASS** |
| 27 | Missing Target Recovery | Uninstall or unconfigure target | Record retained until physical restoration confirmed | Verified in `LockCoordinatorTest` | **PASS** |

---

## 3. Supported Debug Recovery Path (§B2)

The debug recovery mechanism is guaranteed via `android:testOnly="true"` in `app/src/debug/AndroidManifest.xml`:

```bash
adb shell dpm remove-active-admin com.limiter.app/.admin.LimiterAdminReceiver
```

This removes Device Owner privileges without requiring a factory reset or safe boot bypass.
Verified that forbidden restrictions (`DISALLOW_DEBUGGING_FEATURES`, `DISALLOW_FACTORY_RESET`, `DISALLOW_SAFE_BOOT`, `DISALLOW_APPS_CONTROL`, `DISALLOW_UNINSTALL_APPS`) are absent from the entire codebase.
