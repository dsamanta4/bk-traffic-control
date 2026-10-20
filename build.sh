#!/usr/bin/env bash
# Builds the debug APK using the local JDK 11 + Gradle 7.6.4 toolchain in
# ../android-build-tools. See README.md for why JDK 11 specifically.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS="$SCRIPT_DIR/../android-build-tools"

if [ ! -d "$TOOLS/jdk11" ] || [ ! -d "$TOOLS/gradle7" ]; then
    echo "Toolchain not found at $TOOLS (expected jdk11/ and gradle7/)." >&2
    echo "Either restore it, or open this project in Android Studio instead." >&2
    exit 1
fi

export JAVA_HOME="$TOOLS/jdk11"
export PATH="$JAVA_HOME/bin:$TOOLS/gradle7/bin:$PATH"

cd "$SCRIPT_DIR"
gradle assembleDebug --no-daemon "$@"

echo
echo "APK: $SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"
