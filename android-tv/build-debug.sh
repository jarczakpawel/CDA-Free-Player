#!/bin/sh
set -eu
cd "$(dirname "$0")"
if [ -x ./gradlew ]; then
    exec ./gradlew :app:assembleDebug
elif command -v gradle >/dev/null 2>&1; then
    exec gradle :app:assembleDebug
else
    echo "Gradle not found. Open android-tv in Android Studio or install Gradle 8.13." >&2
    exit 2
fi
