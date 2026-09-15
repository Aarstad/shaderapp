#!/usr/bin/env bash
# Build in the proot container, install over adb, launch. One command per iteration.
set -euo pipefail
cd "$(dirname "$0")"

DISTRO=${DISTRO:-ubuntu}
APK=shader.apk
PKG=dev.aarstad.shader

echo "[build] proot-distro $DISTRO"
proot-distro login "$DISTRO" --bind "$PWD:/mnt/shaderapp" -- \
  /bin/bash -c "cd /mnt/shaderapp && ./build.sh"

echo
if ! adb get-state >/dev/null 2>&1; then
  cat <<'MSG'
[adb] no device connected.

Turn on Wireless debugging (Developer options), then pair once per reboot:

  adb pair 127.0.0.1:<pairing-port>    # port + 6-digit code from
                                       # "Pair device with pairing code"
  adb connect 127.0.0.1:<port>         # port from the Wireless debugging screen
                                       # (different from the pairing port)
MSG
  exit 1
fi

echo "[install] $APK"
adb install -r "$APK"

echo "[launch] $PKG"
adb shell am start -n "$PKG/.MainActivity"
