#!/usr/bin/env bash
# Compile plugin/src into a dex for pushing. Runs in the proot container, same
# as build.sh -- it needs the same javac, android.jar and dexer.
#
# The plugin compiles against the app's own build/classes so that Plugin and
# Plugin.Host resolve to the exact classes the running app holds; only the
# plugin's own classes are dexed, so neither the interface nor the Kotlin
# stdlib is duplicated -- both already live in the APK.
#
# Kotlin is compiled first, then Java, so Java can call into Kotlin. Going the
# other way in the same module would need kotlinc's -Xjava-source-roots.
set -euo pipefail
cd "$(dirname "$0")"

SDK=${SDK:-/opt/shader-sdk}
ANDROID_JAR="$SDK/android-34.jar"
D8_JAR="$SDK/d8.jar"
KOTLINC="$SDK/kotlinc/bin/kotlinc"
KOTLIN_STDLIB="$SDK/kotlinc/lib/kotlin-stdlib.jar"
OUT=plugin/build

if [ ! -d build/classes ]; then
  echo "build/classes is missing -- run ./build.sh first, the plugin links against it" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/classes"

KT=$(find plugin/src -name '*.kt')
JAVA=$(find plugin/src -name '*.java')

if [ -n "$KT" ]; then
  echo "[kotlinc] $(echo "$KT" | wc -l) file(s)"
  "$KOTLINC" -nowarn -jvm-target 17 \
    -classpath "$ANDROID_JAR:build/classes" \
    -d "$OUT/classes" $KT
fi

if [ -n "$JAVA" ]; then
  echo "[javac] $(echo "$JAVA" | wc -l) file(s)"
  javac --release 17 -nowarn -Xlint:-options \
    -classpath "$ANDROID_JAR:build/classes:$OUT/classes:$KOTLIN_STDLIB" \
    -d "$OUT/classes" \
    $JAVA
fi

# --classpath, not input: these types exist at runtime in the APK, so they must
# resolve during dexing but must not be dexed into the plugin.
echo "[d8]"
java -cp "$D8_JAR" com.android.tools.r8.D8 \
  --release \
  --min-api 21 \
  --lib "$ANDROID_JAR" \
  --classpath build/classes \
  --classpath "$KOTLIN_STDLIB" \
  --output "$OUT" \
  $(find "$OUT/classes" -name '*.class')

ls -la "$OUT/classes.dex"
