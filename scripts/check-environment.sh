#!/usr/bin/env bash
set -e

echo "=== Environment Check ==="

# 1. Java Version & JAVA_HOME
JAVA_CMD="java"
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
elif ! command -v java >/dev/null 2>&1 && [ -x "/usr/lib/jvm/java-17-openjdk-amd64/bin/java" ]; then
    JAVA_CMD="/usr/lib/jvm/java-17-openjdk-amd64/bin/java"
    export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
fi

if ! command -v "$JAVA_CMD" >/dev/null 2>&1 && [ ! -x "$JAVA_CMD" ]; then
    echo "ERROR: java command not found."
    exit 1
fi

JAVA_VERSION_OUTPUT=$("$JAVA_CMD" -version 2>&1)
echo "Java Version output (using $JAVA_CMD):"
echo "$JAVA_VERSION_OUTPUT"

if ! echo "$JAVA_VERSION_OUTPUT" | grep -q 'version "17\.' && ! echo "$JAVA_VERSION_OUTPUT" | grep -q 'version "17"'; then
    # If default java is not 17, check if JDK 17 is available on standard path
    if [ -x "/usr/lib/jvm/java-17-openjdk-amd64/bin/java" ]; then
        JAVA_CMD="/usr/lib/jvm/java-17-openjdk-amd64/bin/java"
        export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
        JAVA_VERSION_OUTPUT=$("$JAVA_CMD" -version 2>&1)
        echo "Detected JDK 17 at $JAVA_CMD:"
        echo "$JAVA_VERSION_OUTPUT"
    else
        echo "ERROR: Java major version is not 17."
        exit 1
    fi
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
