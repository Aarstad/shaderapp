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
| Ripple | Radial wave, damped with distance |
| Touch | Follows `u_touch`/`u_pulse` from the plugin; centres itself without one |

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
| `GET /plugin` | plugin status and recent log |
| `POST /plugin` | body is a dex file; `X-Plugin-Class` optional |
| `DELETE /plugin` | detach it |
| `POST /command` | body is free text for the plugin |

Loopback-bound means only code already on this device can reach it. Any app on
the phone could post to it, and the worst it can do is draw something -- that is
the right trade for a development channel, but it is why the socket closes the
moment the app leaves the foreground.

Two things that make loopback less obvious than it looks, both handled:

- The server binds **127.0.0.1 explicitly**, not `getLoopbackAddress()` -- that
  returns `::1` wherever IPv6 is up, and a socket bound to `::1` refuses IPv4
  connections, which is indistinguishable from the app not running.
- `push.sh` passes `--noproxy '*'`. An exported `http_proxy` (Claude Code sets
  one) otherwise intercepts even loopback requests and answers for the app.

## Live code push

Shaders hot-swap because GLSL is text. Java hot-swaps too, with one hard limit:
**a class already loaded can never be replaced.** Only new classes from a new
loader come in. So the app is split -- everything that must stay put lives in
the APK, and the interesting part lives behind `Plugin`, in a dex that gets
pushed:

    ./push.sh -p              build plugin/ and swap the dex in
    ./push.sh -p some.dex     push a dex you already have
    ./push.sh -c 'cycle 6'    send free text to the plugin
    ./push.sh -i              status and recent log
    ./push.sh -P              detach it

A plugin is a class `dev.aarstad.shader.plugin.Main` with a no-argument
constructor (override with the `X-Plugin-Class` header). It gets every frame on
the GL thread with the drawing program already bound, every touch in shader
space, and free text from `-c`.

`Plugin` is the one thing a push cannot change -- adding a method to it means a
reinstall. That is why it is small and loose: `setUniform()` takes any name and
any arity, `command()` takes any string. The example plugin invents `u_touch`,
`u_pulse` and `u_down`, plus a `cycle`/`decay`/`status` command vocabulary, and
the app knows about none of them.

Because a uniform the current shader doesn't declare resolves to -1 and is
dropped, one plugin can feed a whole cycle of unrelated shaders and each picks
up only what it declares.

### Not crashing

Plugin code is hostile by assumption. Every call into it is wrapped in a catch
of `Throwable`: one that throws is logged, detached mid-frame, and dropped from
the reload pointer, and the app carries on with its built-in behaviour.

That handles throwing. It does not handle hanging or dying inside `attach()`,
which would take the GL thread down -- and since plugins reload at launch, that
is a crash loop. So loading is *armed*: a marker file is written before a plugin
first runs and cleared once it has survived a frame. Finding that marker at
startup means the last attempt never got that far, so the plugin is left
disabled and reported instead of loaded again.

Swapping a plugin leaks its predecessor's classes -- the old loader is dropped,
but loaded classes are collected only once nothing references them, and the
runtime is conservative. A few KB per swap on a development channel is a fair
price for not restarting.

### Building

`build.sh` runs the whole APK pipeline by hand:

    aapt -> javac -> d8 -> zipalign -> apksigner

**This does not build under native Termux**, but it does build on the phone --
inside the proot Ubuntu container:

    proot-distro login ubuntu
    cd /mnt/shaderapp && ./tools/setup-sdk.sh   # once
    ./build.sh

The reason the whole thing is possible on an ARM phone at all is `aapt`.
Google ships the resource compiler as an x86_64-only native binary, which is
normally what stops Android builds working here -- but Debian builds its
`android-sdk` packages from source for every architecture, so `aapt`,
`zipalign` and `apksigner` are all native aarch64 already.

`tools/setup-sdk.sh` fetches the two pieces Debian doesn't ship, into
`/opt/shader-sdk`:

| | |
|---|---|
| `d8.jar` | the dexer, out of build-tools 37 |
| `android-34.jar` | the compile classpath |
| `kotlinc/` | the Kotlin compiler and stdlib |

All of it is architecture independent -- `d8` and `kotlinc` are JVM programs,
`android.jar` and `kotlin-stdlib.jar` are plain jars -- so none of it cares
that this is an ARM phone. Only the two files that are needed get kept out of
build-tools; the rest is x86_64 native binaries that would not run here.

**Use build-tools 37, not 34.** The `d8` in 34 (8.2.2-dev) dies with a
`NullPointerException` while writing the dex on this container's JDK 25. It is
not a class file version problem -- it fails on Java 8 bytecode just the same.
37 ships d8 9.2.4-dev, which is fine. d8 rejects class file major version 65,
so `--release 21` is out; the build uses 17.

This replaced Debian's `dalvik-exchange` (classic `dx`) and `android-23.jar`.
Two things that cost, and no longer do:

- `dx` predates `invokedynamic`, so every callback had to be an anonymous inner
  class. Lambdas work now.
- Compiling against API 23 meant anything newer went in by reflection -- the
  display-cutout code was a `getField().setInt()`. It is an ordinary assignment
  now, still guarded on `SDK_INT` because `minSdk` is 21.

`setSystemUiVisibility` stays, and javac warns about it. Its replacement,
`WindowInsetsController`, arrived in API 30, and the old flags still work
because the app targets SDK 34 -- Android 15+ only ignores them above 34.
Raising `targetSdk` means switching, and that is the change to make together.

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
