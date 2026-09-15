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

## Live shader push

Shaders are not baked into the code. The APK ships a *loader*, and shader
source is pushed into the running app over a loopback socket:

    ./push.sh assets/shaders/Tunnel.frag    compile it and show it, now
    ./push.sh -w assets/shaders/Tunnel.frag watch the file, push on every save
    ./push.sh -l                            list presets, current one starred
    ./push.sh -s Voronoi                    switch preset
    ./push.sh -r Tunnel                     drop the pushed version, restore the built-in

No rebuild, no reinstall, no installer prompt, and the app never restarts --
the picture changes under you. `-w` is the one to use while working: save in
your editor, look up, it has already changed.

**Why this works.** An APK can never replace itself without the package
installer prompting; that is enforced by the OS and no app-side cleverness gets
around it. But GLSL is just text that the GPU driver compiles at runtime, so
the thing worth iterating on never has to be in the APK in the first place.
Only Java changes need a real reinstall.

**Compile errors come back to the terminal.** A failed push prints the driver's
log and leaves the running shader untouched, so you keep looking at the last
good version while you fix it. `push.sh` rewrites the log's line numbers to
match your file -- the driver counts from the top of the concatenated source,
which includes the shared preamble.

    $ ./push.sh assets/shaders/Tunnel.frag
    --- 400 ---
    0:7: S0001: no matching overload for function 'smoothstep'

A name the app has never seen is *appended* to the cycle, so new presets need
no reinstall either:

    cp assets/shaders/Plasma.frag assets/shaders/Ripple.frag
    $EDITOR assets/shaders/Ripple.frag
    ./push.sh assets/shaders/Ripple.frag     # sixth preset, live

Pushed shaders are written to the app's private storage, so they survive the
app being killed. `-r` deletes that copy: the name reverts to the built-in if
the APK has one, and drops out of the cycle if it does not.

### The channel

`ShaderServer` is a small HTTP server bound to `127.0.0.1`, running only while
the activity is in the foreground. The app takes the first free port from 8777
upward and toasts it at startup; `push.sh` probes that range to find it.

| | |
|---|---|
| `GET /health` | version, preamble line count, preset list |
| `GET /presets` | names in cycle order, current one starred |
| `POST /preset/<Name>` | body is fragment shader source; compiles and shows it |
| `DELETE /preset/<Name>` | revert to the built-in |
| `POST /select/<Name>` | switch preset (index also accepted) |

Loopback-bound means only code already on this device can reach it. Any app on
the phone could post to it, and the worst it can do is draw something -- that is
the right trade for a development channel, but it is why the socket closes the
moment the app leaves the foreground.

## How it draws

`MainActivity` puts a `GLSurfaceView` in continuous render mode and keeps the
screen on. `PresetRenderer` draws a single fullscreen triangle (cheaper than a
quad, and it needs no index buffer); all the work happens in the fragment
shader.

Every shader is GLSL ES 1.00 and takes the same two uniforms -- `u_res` in
pixels and `u_time` in seconds -- so the draw path never special-cases one.
They share `_head.glsl`, a preamble providing `centred()` (an aspect-corrected
uv in [-1,1]) and `palette()` (the cosine palette). Programs are compiled at
surface creation and swapped by index, so cycling costs nothing at the tap.

`u_time` is continuous across switches -- presets don't restart when you tap.

A push compiles and links the new program to completion *before* replacing
anything, so a broken shader can't interrupt what's on screen. If a shader that
was saved earlier stops compiling -- a different driver, or a context loss after
a bad save -- it falls back to a red test pattern rather than crashing at
launch, and `push.sh -l` marks it.

These target a mid-range Mali at 1080p/60. Loop counts are the knob to turn
first if a preset drops frames.

## Where shaders live

    assets/shaders/_head.glsl     shared preamble, prepended to every shader
    assets/shaders/order.txt      cycle order for the built-ins
    assets/shaders/<Name>.frag    one shader per preset

Those are packaged into the APK as assets. At runtime the app overlays anything
pushed (in `getFilesDir()/presets/`) on top, matching by name.

Preset names are used as filenames and echoed into HTTP responses, so they are
restricted to letters, digits, `_` and `-`.

To make a pushed shader permanent, save it under `assets/shaders/`, add the
name to `order.txt`, rebuild, and `./push.sh -r <Name>` to clear the overlay
copy that would otherwise keep shadowing it.

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

Avoid lambdas and other Java 8+ desugaring: `dalvik-exchange` is classic `dx`
and does not handle `invokedynamic`. Anonymous inner classes only.

## Installing

Only needed when the Java changes -- shader edits go through `push.sh`.

`./deploy.sh` builds, then installs over adb if a device is connected and hands
the APK to the system package installer if not. The installer path costs one
tap and always works; adb is hands-free but flakier on this ROM, since the
connect port changes every time `adbd` restarts and Wireless debugging does not
survive a reboot.

    adb pair 127.0.0.1:<pairing-port>    # 6-digit code from "Pair device with pairing code"
    adb connect 127.0.0.1:<port>         # from the Wireless debugging screen, a different number

127.0.0.1 works because Termux is on the same device it is debugging.

`deploy.sh` and `push.sh` run in Termux and hardcode the Termux `bash` path,
because Termux has no `/usr/bin/env`. `build.sh` keeps `env(1)` -- it only ever
runs inside the container.

## Signing

`build.sh` generates `debug.keystore` on first run (storepass/keypass
`android`, alias `debug`) if it is missing. That file is gitignored, so a fresh
clone signs with a *new* key -- the resulting APK will not install over an
existing copy without uninstalling first. To keep one stable signing identity
across machines, drop the `debug.keystore` line from `.gitignore` and commit the
file; it is a throwaway debug key, not a release key.
