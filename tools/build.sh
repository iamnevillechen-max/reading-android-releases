#!/bin/sh
set -eu
PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SHARED_TOOLS="$PROJECT_ROOT/../sightmate-android/.toolchain"
if [ -z "${JAVA_HOME:-}" ] && [ -x "$SHARED_TOOLS/jdk-17.0.21+9/Contents/Home/bin/java" ]; then
    export JAVA_HOME="$SHARED_TOOLS/jdk-17.0.21+9/Contents/Home"
fi
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
if [ -z "${GRADLE_USER_HOME:-}" ] && [ -d "$SHARED_TOOLS/gradle-home" ]; then
    export GRADLE_USER_HOME="$SHARED_TOOLS/gradle-home"
fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$ANDROID_SDK_ROOT/platform-tools:$PATH"
SOURCE_ROOT="${LEGADO_SOURCE_ROOT:-$PROJECT_ROOT/upstream}"
cd "$SOURCE_ROOT"
if [ "$#" -eq 0 ]; then set -- :app:assembleAppDebug :app:testAppDebugUnitTest; fi
if [ -x "$PROJECT_ROOT/.toolchain/gradle-8.13/bin/gradle" ]; then
    GRADLE_EXECUTABLE="$PROJECT_ROOT/.toolchain/gradle-8.13/bin/gradle"
else
    GRADLE_EXECUTABLE="./gradlew"
fi
exec "$GRADLE_EXECUTABLE" --no-daemon --console=plain --max-workers=2 -Dorg.gradle.jvmargs='-Xmx2g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8' "$@"
