#!/usr/bin/env bash
set -e

echo "=== Environment Check ==="

# 1. Java Version
if ! command -v java >/dev/null 2>&1; then
    echo "ERROR: java command not found in PATH."
    exit 1
fi

JAVA_VERSION_OUTPUT=$(java -version 2>&1)
echo "Java Version output:"
echo "$JAVA_VERSION_OUTPUT"

if ! echo "$JAVA_VERSION_OUTPUT" | grep -q 'version "17\.' && ! echo "$JAVA_VERSION_OUTPUT" | grep -q 'version "17"'; then
    echo "ERROR: Java major version is not 17."
    exit 1
fi
echo "OK: Java 17 detected."

# 3. JAVA_HOME
if [ -z "$JAVA_HOME" ]; then
    echo "WARNING: JAVA_HOME is not set."
else
    echo "JAVA_HOME: $JAVA_HOME"
fi

# 4. Android SDK Path
SDK_PATH="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
echo "Android SDK path evaluated to: $SDK_PATH"
if [ ! -d "$SDK_PATH" ]; then
    echo "ERROR: Android SDK directory not found at $SDK_PATH"
    exit 1
fi
echo "OK: Android SDK directory exists."

# 5. Verify compileSdk 34 availability
if [ ! -d "$SDK_PATH/platforms/android-34" ]; then
    echo "ERROR: platforms/android-34 is not installed in the SDK."
    exit 1
fi
echo "OK: compileSdk 34 is available."

# 6. Verify required build-tools
BUILD_TOOLS_FOUND=$(find "$SDK_PATH/build-tools" -mindepth 1 -maxdepth 1 -type d -name "34.*" | wc -l)
if [ "$BUILD_TOOLS_FOUND" -eq 0 ]; then
    echo "WARNING: No build-tools 34.* found."
else
    echo "OK: build-tools 34.* is available."
fi

# 7. Gradle Version
if [ -f "./gradlew" ]; then
    echo "Checking Gradle version..."
    ./gradlew --version | grep -E "Gradle" || true
else
    echo "ERROR: ./gradlew not found in current directory."
    exit 1
fi

echo "=== Environment Check PASSED ==="
exit 0
