# shader

A GLES 2.0 domain-warped plasma, rendered fullscreen on Android. One activity,
one fragment shader, no Gradle.

Package: `dev.aarstad.shader`

## How it draws

`MainActivity` puts a `GLSurfaceView` in continuous render mode and keeps the
screen on. `PlasmaRenderer` draws a single fullscreen triangle (cheaper than a
quad, and it needs no index buffer); all the work happens in the fragment
shader, which warps the UV domain five times, colours the result with a cosine
palette, and applies a vignette. Five warp iterations is about the most a
mid-range Mali will hold at 60fps on a 1080p-class screen.

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

## Signing

`build.sh` generates `debug.keystore` on first run (storepass/keypass
`android`, alias `debug`) if it is missing. That file is gitignored, so a fresh
clone signs with a *new* key -- the resulting APK will not install over an
existing copy without uninstalling first. To keep one stable signing identity
across machines, drop the `debug.keystore` line from `.gitignore` and commit the
file; it is a throwaway debug key, not a release key.
