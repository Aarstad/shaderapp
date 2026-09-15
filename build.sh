#!/usr/bin/env bash
# Manual APK pipeline -- no Gradle. aapt -> javac -> d8 -> zipalign -> apksigner.
#
# aapt, zipalign and apksigner come from Debian's android-sdk packages, which
# Debian builds from source for every architecture -- so they are native aarch64
# here. That matters: Google ships aapt2 as an x86_64-only binary, and it is
# normally what stops Android builds working on an ARM phone.
#
# d8 and android.jar are not in those packages; tools/setup-sdk.sh fetches them.
# Both are architecture independent (d8 is a Java jar, android.jar a classpath
# stub), so they run here unchanged.
set -euo pipefail
cd "$(dirname "$0")"

SDK=${SDK:-/opt/shader-sdk}
ANDROID_JAR="$SDK/android-34.jar"
D8_JAR="$SDK/d8.jar"
KOTLIN_STDLIB="$SDK/kotlinc/lib/kotlin-stdlib.jar"
MIN_SDK=21
TARGET_SDK=34
OUT=build

if [ ! -f "$ANDROID_JAR" ] || [ ! -f "$D8_JAR" ]; then
  echo "missing $SDK -- run ./tools/setup-sdk.sh first (inside the container)" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/6] aapt: resources + R.java + base apk"
aapt package -f -m \
  -J "$OUT/gen" \
  -M AndroidManifest.xml \
  -S res \
  -A assets \
  -I "$ANDROID_JAR" \
  -F "$OUT/base.apk" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK"

echo "[2/6] javac"
javac --release 17 -nowarn -Xlint:-options \
  -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" \
  $(find src "$OUT/gen" -name '*.java')

# The Kotlin stdlib ships in the APK rather than in pushed dex. It is
# infrastructure -- the same side of the line as the Plugin interface -- so a
# Kotlin plugin stays a few KB per push instead of carrying 1.8 MB, and a swap
# doesn't reload the whole stdlib. Costs about a megabyte of APK; R8 with keep
# rules would trim it if that ever mattered.
echo "[3/6] d8: classes + kotlin stdlib -> dex"
java -cp "$D8_JAR" com.android.tools.r8.D8 \
  --release \
  --min-api "$MIN_SDK" \
  --lib "$ANDROID_JAR" \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class') \
  "$KOTLIN_STDLIB"
cp "$OUT"/dex/classes*.dex "$OUT/"

echo "[4/6] aapt add: dex into apk"
# Plural on purpose: stdlib could push us past the 64K method limit into
# classes2.dex, and d8 names the overflow itself.
( cd "$OUT" && aapt add -f base.apk classes*.dex >/dev/null )
echo "      dex files: $(cd "$OUT" && ls classes*.dex | tr '\n' ' ')"

echo "[5/6] zipalign"
zipalign -f 4 "$OUT/base.apk" "$OUT/shader-unsigned.apk"

echo "[6/6] sign"
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore \
    -storepass android -keypass android -alias debug \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Shader Debug, O=cwrap, C=NO" >/dev/null 2>&1
fi
apksigner sign \
  --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out shader.apk "$OUT/shader-unsigned.apk"

echo
apksigner verify --print-certs shader.apk | head -4
ls -la shader.apk
