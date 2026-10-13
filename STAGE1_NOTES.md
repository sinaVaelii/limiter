# Stage 1 Notes

## Created
- `settings.gradle.kts`
- `build.gradle.kts`
- `gradle.properties`
- `.gitignore` (ignoring local config and keys)
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/debug/AndroidManifest.xml` (with testOnly="true")
- `app/src/main/java/com/limiter/app/MainActivity.kt`
- `app/src/main/java/com/limiter/app/admin/LimiterAdminReceiver.kt`
- `app/src/main/res/xml/device_admin.xml`
- `app/src/test/java/com/limiter/app/Stage1SanityTest.kt`
- `.github/workflows/build.yml`
- Generated local `limiter.jks` and `keystore.properties`
- `AGENTS.md` (renamed from `AGENENTS.md`)

## Design Decisions
- Fall-safe interpretation: GitHub Actions workflow requires a dummy keystore generated on-the-fly to successfully build `assembleDebug` since `keystore.properties` is ignored. Added keystore generation to the workflow.
- JDK 17 installed locally and used for Gradle build.

## Build Results
- `assembleDebug` and `testDebugUnitTest` run successfully.
- Local signing properties enforced gracefully.
