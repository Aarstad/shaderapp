#!/usr/bin/env bash
# Compile plugin/src into a dex for pushing. Runs in the proot container, same
# as build.sh -- it needs the same javac, android.jar and dexer.
#
# The plugin compiles against the app's own build/classes so that Plugin and
# Plugin.Host resolve to the exact classes the running app holds; only the
# plugin's own classes are dexed, so the interface is never duplicated.
set -euo pipefail
cd "$(dirname "$0")"

SDK=${SDK:-/opt/shader-sdk}
ANDROID_JAR="$SDK/android-34.jar"
D8_JAR="$SDK/d8.jar"
OUT=plugin/build

if [ ! -d build/classes ]; then
  echo "build/classes is missing -- run ./build.sh first, the plugin links against it" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/classes"

echo "[1/2] javac"
javac --release 17 -nowarn -Xlint:-options \
  -classpath "$ANDROID_JAR:build/classes" \
  -d "$OUT/classes" \
  $(find plugin/src -name '*.java')

echo "[2/2] d8"
java -cp "$D8_JAR" com.android.tools.r8.D8 \
  --release \
  --min-api 21 \
  --lib "$ANDROID_JAR" \
  --classpath build/classes \
  --output "$OUT" \
  $(find "$OUT/classes" -name '*.class')

ls -la "$OUT/classes.dex"
