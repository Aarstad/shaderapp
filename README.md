# shader

A set of fullscreen GLES 2.0 shaders for Android. One activity, no Gradle.
**Tap the screen to cycle presets.**

Package: `dev.aarstad.shader`

## Presets

| | |
|---|---|
| Plasma | Domain-warped noise, five warp iterations |
| Tunnel | Perspective tunnel; depth goes as 1/r, so rings bunch toward the centre |
| Kaleidoscope | Six-fold angular mirror over an inversion fold |
| Metaballs | Four inverse-distance blobs, thresholded so they fuse |
| Voronoi | Animated cells, shaded on the gap between the two nearest seeds |

## How it draws

`MainActivity` puts a `GLSurfaceView` in continuous render mode and keeps the
screen on. `PresetRenderer` draws a single fullscreen triangle (cheaper than a
quad, and it needs no index buffer); all the work happens in the fragment
shader.

Every preset in `Presets.java` is GLSL ES 1.00 and takes the same two uniforms
-- `u_res` in pixels and `u_time` in seconds -- so the draw path never
special-cases one. They share a preamble providing `centred()` (an
aspect-corrected uv in [-1,1]) and `palette()` (the cosine palette). All
programs are compiled once at surface creation and swapped by index, so cycling
costs nothing at the tap.

`u_time` is continuous across switches -- presets don't restart when you tap.

These target a mid-range Mali at 1080p/60. Loop counts are the knob to turn
first if a preset drops frames.

## Adding a preset

Write the fragment shader in `Presets.java` against that preamble, then add it
to `NAMES` and `SOURCES` -- they are parallel arrays and the renderer sizes
itself off them. Nothing else needs touching.

## Building

`build.sh` runs the whole APK pipeline by hand:

    aapt -> javac -> dalvik-exchange (dx) -> aapt add -> zipalign -> apksigner

**This does not build under native Termux.** It needs Debian's `android-sdk`
packages -- `aapt`, `dalvik-exchange`, `zipalign`, `apksigner`, and
`/usr/lib/android-sdk/platforms/android-23/android.jar` -- none of which are in
the Termux repos. Build it inside the proot Ubuntu container:

    proot-distro login ubuntu
    cd /root/shaderapp && ./build.sh

Adjust `ANDROID_JAR` at the top of `build.sh` if the SDK lives elsewhere.

## Installing

`./deploy.sh` builds, installs over adb and launches, in one step.

adb talks to this phone over Wireless debugging (Developer options), which also
sidesteps the package installer's Play Protect prompt on every sideload. Pair
once per reboot -- the ports are on the Wireless debugging screen, and the
pairing port differs from the connect port:

    adb pair 127.0.0.1:<pairing-port>    # 6-digit code from "Pair device with pairing code"
    adb connect 127.0.0.1:<port>

127.0.0.1 works because Termux is on the same device it is debugging.

Without adb, `termux-open shader.apk` hands the APK to the system installer
instead.

## Signing

`build.sh` generates `debug.keystore` on first run (storepass/keypass
`android`, alias `debug`) if it is missing. That file is gitignored, so a fresh
clone signs with a *new* key -- the resulting APK will not install over an
existing copy without uninstalling first. To keep one stable signing identity
across machines, drop the `debug.keystore` line from `.gitignore` and commit the
file; it is a throwaway debug key, not a release key.
