#!/usr/bin/env bash
# Fetch the two pieces Debian's android-sdk doesn't ship: a modern dexer and a
# modern android.jar. Runs inside the proot container.
#
# Only two files are kept. The rest of build-tools is x86_64 native binaries
# that would not run on this phone anyway -- but d8 is a self-contained Java
# jar, and android.jar is just a classpath stub, so both are architecture
# independent and work fine on aarch64.
#
# build-tools 37 rather than 34 on purpose: the d8 in 34 (8.2.2-dev) dies with
# an NPE while writing the dex when it runs on this container's JDK 25. It is
# not a class file version problem -- it fails on Java 8 bytecode too. 37 ships
# d8 9.2.4-dev, which is fine on 25.
set -euo pipefail

SDK=${SDK:-/opt/shader-sdk}
BASE=https://dl.google.com/android/repository
BUILD_TOOLS=build-tools_r37_linux.zip
PLATFORM=platform-34-ext7_r03.zip

KOTLIN_VERSION=2.4.20
KOTLIN_ZIP=kotlin-compiler-$KOTLIN_VERSION.zip
KOTLIN_URL=https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VERSION/$KOTLIN_ZIP

mkdir -p "$SDK"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

fetch() {  # name
  echo "[fetch] $1"
  curl -fL --noproxy '*' -m 900 --retry 2 -o "$tmp/$1" "$BASE/$1"
}

# Pull one entry out of a zip regardless of the version-numbered top directory
# the archive happens to use.
extract() {  # zip suffix dest
  local zip=$1 suffix=$2 dest=$3 entry
  entry=$(unzip -Z1 "$tmp/$zip" | grep -E "(^|/)$suffix\$" | head -1)
  [ -n "$entry" ] || { echo "no $suffix inside $zip" >&2; exit 1; }
  echo "[extract] $entry -> $dest"
  unzip -p "$tmp/$zip" "$entry" > "$dest"
}

if [ ! -f "$SDK/d8.jar" ]; then
  fetch "$BUILD_TOOLS"
  extract "$BUILD_TOOLS" "lib/d8.jar" "$SDK/d8.jar"
fi

if [ ! -f "$SDK/android-34.jar" ]; then
  fetch "$PLATFORM"
  extract "$PLATFORM" "android.jar" "$SDK/android-34.jar"
fi

# Kotlin, for writing plugins in. The compiler is a JVM program and the stdlib
# is a plain jar, so like d8 neither cares about the architecture.
if [ ! -x "$SDK/kotlinc/bin/kotlinc" ]; then
  echo "[fetch] $KOTLIN_ZIP"
  curl -fL --noproxy '*' -m 900 --retry 2 -o "$tmp/$KOTLIN_ZIP" "$KOTLIN_URL"
  echo "[extract] kotlinc -> $SDK/kotlinc"
  unzip -q -o "$tmp/$KOTLIN_ZIP" -d "$tmp/ktc"
  rm -rf "$SDK/kotlinc"
  mv "$tmp/ktc/kotlinc" "$SDK/kotlinc"
  chmod +x "$SDK/kotlinc/bin/"*
fi

echo
echo "[verify] d8"
java -cp "$SDK/d8.jar" com.android.tools.r8.D8 --version
echo "[verify] android.jar: $(unzip -l "$SDK/android-34.jar" | tail -1)"
echo "[verify] kotlin"
"$SDK/kotlinc/bin/kotlinc" -version 2>&1 | head -1
ls -la "$SDK/kotlinc/lib/kotlin-stdlib.jar"
ls -la "$SDK"
