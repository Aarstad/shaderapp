#!/usr/bin/env bash
# Compile plugin/src into a dex for pushing. Runs in the proot container, same
# as build.sh -- it needs the same javac, android.jar and dexer.
#
# The plugin compiles against the app's own build/classes so that Plugin and
# Plugin.Host resolve to the exact classes the running app holds; only the
# plugin's own classes are dexed, so the interface is never duplicated.
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR=/usr/lib/android-sdk/platforms/android-23/android.jar
OUT=plugin/build

if [ ! -d build/classes ]; then
  echo "build/classes is missing -- run ./build.sh first, the plugin links against it" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/classes"

echo "[1/2] javac"
javac --release 8 -nowarn -Xlint:-options \
  -classpath "$ANDROID_JAR:build/classes" \
  -d "$OUT/classes" \
  $(find plugin/src -name '*.java')

echo "[2/2] dx"
dalvik-exchange --dex --output="$OUT/classes.dex" "$OUT/classes"

ls -la "$OUT/classes.dex"
