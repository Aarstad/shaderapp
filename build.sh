#!/usr/bin/env bash
# Manual APK pipeline -- no Gradle. aapt -> javac -> dx -> zipalign -> apksigner.
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR=/usr/lib/android-sdk/platforms/android-23/android.jar
MIN_SDK=21
TARGET_SDK=34
OUT=build

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes"

echo "[1/6] aapt: resources + R.java + base apk"
aapt package -f -m \
  -J "$OUT/gen" \
  -M AndroidManifest.xml \
  -S res \
  -I "$ANDROID_JAR" \
  -F "$OUT/base.apk" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK"

echo "[2/6] javac"
javac --release 8 -nowarn -Xlint:-options \
  -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" \
  $(find src "$OUT/gen" -name '*.java')

echo "[3/6] dx: classes -> dex"
dalvik-exchange --dex --output="$OUT/classes.dex" "$OUT/classes"

echo "[4/6] aapt add: dex into apk"
( cd "$OUT" && aapt add -f base.apk classes.dex >/dev/null )

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
