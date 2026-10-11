# Limiter 🔒

> **یه اپ اندروید برای محدود کردن خودت — کاملاً آفلاین، بدون دیتا، بدون بهانه.**
>
> *An Android app to lock yourself out of time-wasting apps — fully offline, no data, no excuses.*

---

## این اپ چیه؟ / What is this?

**Limiter** یه اپ شخصی‌سازی‌شده برای اندروید ۱۵ روی دستگاه شخصی‌ام (Xiaomi Redmi 12) هست.

دو تا کار می‌کنه:
1. **محدودیت روزانه** — اگه بیشتر از X دقیقه یه اپ رو باز کنی، قفل می‌شه تا فردا صبح
2. **پنجره شبانه** — بین ساعت مشخصی (مثلاً ۲۳:۰۰ تا ۷:۰۰) همه اپ‌های تعریف‌شده قفل می‌مونن

قفل واقعیه — اپ‌ها hidden + suspended می‌شن از طریق Android Device Owner API. نه overlay، نه ترفند، نه راه فرار اپی.

---

**Limiter** is a personal Android app built for Xiaomi Redmi 12 (Android 15).

It does two things:
1. **Daily limit** — if you use an app for more than X minutes, it locks until tomorrow morning
2. **Nightly window** — between set hours (e.g. 23:00–07:00), all configured apps stay locked

The lock is real — apps get `hidden + suspended` via Android Device Owner API. No overlay tricks, no app-level bypass, no snooze button.

---

## ویژگی‌ها / Features

| ویژگی | جزئیات |
|---|---|
| 🔒 قفل واقعی | `hidden + suspended` از طریق DPM — بدون overlay |
| 📵 کاملاً آفلاین | هیچ دسترسی به اینترنتی نداره (`INTERNET` permission نداره) |
| 🌙 قفل شبانه | پنجره زمانی مشخص با صفحه تمام‌صفحه + تایمر شمارش معکوس |
| ⏱ محدودیت روزانه | ۱ تا ۱۴۴۰ دقیقه در روز، قابل تنظیم |
| 🛡 محافظت از خودش | نمیشه حذف یا force stop کرد — مگر از طریق ADB |
| 🔄 ریکاوری خودکار | بعد از ریبوت state رو بازیابی می‌کنه و قفل‌های ناتمام رو ترمیم می‌کنه |

---

## نصب و راه‌اندازی / Setup

> ⚠️ این اپ نیاز به **Device Owner** داره. باید روی دستگاه تازه‌ریست‌شده (یا قبل از نصب هر اپ دیگه‌ای) راه‌اندازی بشه.

### مرحله ۱ — نصب APK / Step 1 — Install APK

```bash
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

### مرحله ۲ — تنظیم Device Owner / Step 2 — Set Device Owner

```bash
adb shell dpm set-device-owner com.limiter.app/.admin.LimiterAdminReceiver
```

### مرحله ۳ — دسترسی‌های لازم / Step 3 — Required Permissions

```bash
# دسترسی به آمار مصرف اپ‌ها / Usage stats
adb shell appops set com.limiter.app GET_USAGE_STATS allow

# اجازه نوتیفیکیشن (اندروید ۱۳+) / Notifications (Android 13+)
adb shell pm grant com.limiter.app android.permission.POST_NOTIFICATIONS

# آلارم دقیق / Exact alarm
adb shell appops set com.limiter.app SCHEDULE_EXACT_ALARM allow
```

---

## اگه اوضاع خراب شد / Emergency Recovery

حذف Device Owner:
```bash
adb shell dpm remove-active-admin com.limiter.app/.admin.LimiterAdminReceiver
```

اگه یه اپ قفل مونده بود:
```bash
adb shell pm set-application-hidden com.example.target false
adb shell pm unsuspend com.example.target
```

> ✅ ADB، factory reset و safe boot همیشه فعال‌ان — این اپ اون‌ها رو غیرفعال نمی‌کنه.

---

## بیلد / Build

```bash
# تست‌ها — ۱۶۴ تست، ۰ خطا / Tests — 164 tests, 0 failures
./gradlew testDebugUnitTest --offline

# بیلد debug
./gradlew assembleDebug --offline

# بیلد release (نیاز به limiter.jks)
./gradlew assembleRelease --offline
```

---

## امنیت کلید / Signing Key

فایل `limiter.jks` خارج از git نگه داشته می‌شه. این کلید رو **هرگز** گم نکن — بدونش نمی‌تونی آپدیت معتبر بدی.

```bash
# تأیید امضای APK / Verify APK signature
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
```

---

## نکته Xiaomi / MIUI Note

روی MIUI/HyperOS:
- **Autostart** برای Limiter فعال باشه
- **Battery Saver** روی "No restrictions" باشه

وگرنه `UsageMonitorService` در حالت deep sleep متوقف می‌شه و قفل‌ها درست کار نمی‌کنن.

---

*ساخته‌شده برای استفاده شخصی روی Xiaomi Redmi 12 / Android 15.*
