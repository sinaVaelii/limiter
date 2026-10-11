# Recovery Notes

## Environment Persistent Configuration
The build environment must exist OUTSIDE the Git repository and survive across chat sessions.

### JDK 17
- **Required Version:** JDK 17
- **Current Status:** Failed to install cleanly due to `apt-get` throttling and `sdkman` network failure.
- **Expected Action by Human:** 
  Run `sudo apt-get install openjdk-17-jdk` manually outside the agent session, or install Eclipse Temurin 17.
- **JAVA_HOME Configuration:**
  Once installed, ensure `JAVA_HOME` is exported in `~/.profile`, `~/.bashrc`, or `~/.zshrc`.
  Example: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`

### Android SDK
- **Installation Location:** `$HOME/Android/Sdk`
- **Required Components:** `compileSdk 34`, `targetSdk 34`, `build-tools 34.0.0`
- **Verification:** Run `./scripts/check-environment.sh` to ensure the SDK path and compile API 34 are correctly installed.

## Recovery From Failed Setup
If tests fail to compile, or `./gradlew` errors out:
1. Verify `java -version` returns exactly a 17.x release.
2. Run `./scripts/check-environment.sh` which performs validation of JDK and SDK constraints.
3. Fix the blockers mentioned by the script before handing the workspace to the next agent.
