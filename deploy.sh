#!/data/data/com.termux/files/usr/bin/bash
# Runs on the phone, in Termux -- which has no /usr/bin/env, hence the
# absolute interpreter. build.sh keeps env(1) since it runs in the container.
#
# Rebuild the APK and get it onto the device. Only needed when Java changes --
# for shader work use push.sh, which needs neither a build nor an install.
set -euo pipefail
cd "$(dirname "$0")"

DISTRO=${DISTRO:-ubuntu}
APK=shader.apk
PKG=dev.aarstad.shader

echo "[build] proot-distro $DISTRO"
proot-distro login "$DISTRO" --bind "$PWD:/mnt/shaderapp" -- \
  /bin/bash -c "cd /mnt/shaderapp && ./build.sh"

echo
if adb get-state >/dev/null 2>&1; then
  echo "[install] adb -> $APK"
  adb install -r "$APK"
  echo "[launch] $PKG"
  adb shell am start -n "$PKG/.MainActivity"
  exit 0
fi

# No adb. Hand the APK to the system package installer instead -- one tap on
# the phone. Wireless debugging is the flakier path on this ROM: its connect
# port changes on every adbd restart and the toggle doesn't survive a reboot,
# so this is the fallback that always works.
echo "[install] no adb device -- opening the package installer"
echo "          tap Update on the prompt that appears"
termux-open --content-type application/vnd.android.package-archive "$PWD/$APK"

cat <<'MSG'

To use adb instead, on the phone:
  Developer options -> Wireless debugging -> on
  then pair once per boot:
    adb pair 127.0.0.1:<pairing-port>   # port + code from "Pair device with pairing code"
    adb connect 127.0.0.1:<port>        # port from the Wireless debugging screen (a different number)
MSG
