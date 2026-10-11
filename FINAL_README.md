# Limiter — Offline Android Application Limiter & Lock Engine

Limiter is a personal-use, offline-only Android application designed for a personally controlled Xiaomi Redmi 12 (Android 15 / API 35) test device. It enforces per-app daily usage limits and scheduled nightly lock windows using Android Device Owner (`DevicePolicyManager`) APIs.

---

## 1. Core Architecture & Operation

1. **Physical Lock Enforcement:**
   - Freezing requires both `hidden == true` and `suspended == true` simultaneously.
   - Restorations physically unfreeze (`suspended = false`, then `hidden = false`) and verify package availability before updating logical state to `Unlocked`.
   - Partial physical states are automatically detected and repaired upon reconciliation.
   - Lock records are monotonically non-decreasing (`max(existingUntil, until)`).

2. **Self-Lock Restricted Mode (R4):**
   - When at least one configured application enters its scheduled lock window (e.g. 23:00 to 07:00), Limiter transitions into a dedicated full-screen Self-Lock screen.
   - The screen features a live countdown timer (`HH:mm:ss`), exact expected unlock timestamp in local date/time, and clear notification of ongoing enforcement.
   - All management controls (app picker, batch config, editing dialogs, target removal) are restricted and rejected by the ViewModel.
   - When the scheduled window expires, Limiter automatically returns to the normal management dashboard.
   - Daily usage limit crossings alone do **not** trigger the global self-lock; their locked state is displayed on their individual target card.

3. **Self-Protection (R3):**
   - Applies targeted `setUninstallBlocked(com.limiter.app, true)` and `setUserControlDisabledPackages(listOf("com.limiter.app"))`.
   - Preserves all standard system recovery and debug paths: ADB debugging, factory reset, safe boot, and recovery mode remain functional.

4. **Zero Network / Total Offline:**
   - Limiter has no `android.permission.INTERNET`.
   - Zero external tracking, analytics, remote configs, or network sockets.

---

## 2. Provisioning & Device Owner Setup

Limiter requires Android Device Owner privileges. To provision on a clean or factory-reset test device:

```bash
# Install debug build (requires testOnly flag)
adb install -r -t app/build/outputs/apk/debug/app-debug.apk

# Grant Device Owner
adb shell dpm set-device-owner com.limiter.app/.admin.LimiterAdminReceiver
```

Required runtime permissions to enable through device settings or ADB:
```bash
# Usage Access (PACKAGE_USAGE_STATS)
adb shell appops set com.limiter.app GET_USAGE_STATS allow

# Notification Permission (POST_NOTIFICATIONS - Android 13+)
adb shell pm grant com.limiter.app android.permission.POST_NOTIFICATIONS

# Schedule Exact Alarm
adb shell appops set com.limiter.app SCHEDULE_EXACT_ALARM allow
```

---

## 3. Supported Debug Recovery

Limiter preserves the official Android Device Owner removal path for debug-signed builds declaring `android:testOnly="true"`:

```bash
adb shell dpm remove-active-admin com.limiter.app/.admin.LimiterAdminReceiver
```

If an app was left hidden/suspended before admin removal, it can be manually restored via ADB:
```bash
adb shell pm set-application-hidden com.example.target false
adb shell pm unsuspend com.example.target
```

---

## 4. Signing Continuity & Key Backup

- **Key File:** `limiter.jks` (located at repository root, outside git tracking).
- **Properties:** `keystore.properties` (gitignored, contains key alias and passwords).
- **Rule:** Never lose or regenerate `limiter.jks`. All debug and release builds must use this key to ensure update continuity.
- **Verification:**
  ```bash
  $ANDROID_HOME/build-tools/34.0.0/apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
  ```

---

## 5. Verification & Test Suite

The project maintains comprehensive pure-JVM test suites with fakes:
- **Test execution:**
  ```bash
  ./gradlew testDebugUnitTest --offline
  ```
  Result: 164 unit tests across 13 test suites (0 failures, 0 skipped).
- **Debug build:**
  ```bash
  ./gradlew assembleDebug --offline
  ```
- **Release build:**
  ```bash
  ./gradlew assembleRelease --offline
  ```

---

## 6. Known Xiaomi / MIUI Device Behaviors

1. **Battery Optimization & Autostart:**
   - In MIUI/HyperOS, ensure Limiter has Autostart enabled and Battery Saver set to "No restrictions" so `UsageMonitorService` is not paused in deep sleep.
2. **App Ops & Exact Alarms:**
   - Android 14/15 requires explicit user grant for exact alarms (`SCHEDULE_EXACT_ALARM`). Limiter alerts the user via the Status card if this permission is missing.
