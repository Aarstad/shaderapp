# shader

Fullscreen GLES 2.0 shaders and live-swappable plugins for Android. Zero Gradle, one Activity, zero-install live reloading.

**Tap the screen to cycle presets.**

* **Package:** `dev.aarstad.shader`
* **Workflow:** Edit shader or plugin code $\to$ save file $\to$ inspect live result on device instantaneously.

---

## Quick Tour

```
┌─────────────────────────────────────────────────────────────┐
│ Android Device (Termux / App)                               │
│                                                             │
│   push.sh -w  ──────── HTTP (127.0.0.1:8777+) ────────►     │
│   (watches files)                                           │
│                         ┌───────────────────────────────┐   │
│                         │ PushServer                    │   │
│                         ├───────────────┬───────────────┤   │
│                         │ Shader Loader │ Plugin Loader │   │
│                         └───────┬───────┴───────┬───────┘   │
│                                 ▼               ▼           │
│                           GPU Driver      DexClassLoader    │
│                           (GLES 2.0)      (Plugin View/Logic│
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

* **No Gradle, no IDE overhead:** Builds directly from source using raw SDK command-line tools (`aapt`, `javac`, `d8`, `zipalign`, `apksigner`).
* **Self-hosted on Android:** Can be built directly inside an ARM Ubuntu container (via PRoot) right on the phone.
* **Instant hot-reloading:** Both fragment shaders (`.frag`) and bytecode (`.dex`) swap into memory over a local loopback server without restarting the app or prompting the package installer.

---

## Presets

The app ships with a curated set of responsive procedural fragment shaders:

| Preset | Visual Behavior & Technique |
|---|---|
| **Plasma** | Multi-octave domain-warped noise with five warp iterations. |
| **Tunnel** | Perspective tunnel; depth scales as $1/r$, creating rings that bunch toward the vanishing point. |
| **Kaleidoscope** | Six-fold angular symmetry mirrored across an inversion fold. |
| **Metaballs** | Four inverse-distance point potentials thresholded to organically merge and separate. |
| **Voronoi** | Animated cellular noise shaded along the Euclidean metric boundary between the two nearest seeds. |
| **Ripple** | Radial damped harmonic wave radiating outward from coordinates. |
| **Touch** | Interactive particle tracker responding to `u_touch` and `u_pulse` from host/plugin (auto-centers when idle). |
| **Electric** | Noise-warped electrical arc quantized against a stepped clock to simulate discharge jitter. |

> **State Persistence:** The app automatically reopens on whichever preset was active when closed (tracked by name rather than index, surviving additions or removals).

---

## Live Shader Push

In typical Android development, testing code changes requires repacking an APK, triggering the OS package installer, and restarting the app.

`shader` avoids this entirely. GLSL source is text compiled on demand by the GPU driver at runtime. Shaders are pushed into the running Activity over a loopback HTTP socket:

```bash
./push.sh assets/shaders/Tunnel.frag    # Compile and display immediately
./push.sh -w assets/shaders/Tunnel.frag # Watch file: pushes instantly on every save
./push.sh -l                            # List all loaded presets (* = active)
./push.sh -s Voronoi                    # Switch active preset
./push.sh -r Tunnel                     # Revert pushed shader back to APK built-in
```

### Key Development Features
* **Zero Interruption:** The graphics context swaps beneath you in a single frame.
* **Inline Driver Diagnostics:** If a shader fails to compile, the driver error log is captured and reported in your terminal. The screen remains on the last working frame.
  ```
  $ ./push.sh assets/shaders/Tunnel.frag
  --- 400 ---
  0:7: S0001: no matching overload for function 'smoothstep'
  ```
* **Correct Error Mapping:** `push.sh` automatically adjusts driver line numbers back to your source file lines, compensating for the shared header preamble (`_head.glsl`).
* **Dynamic Preset Addition:** Pushing an unknown filename automatically appends it to the cycle list—no reinstall needed:
  ```bash
  cp assets/shaders/Plasma.frag assets/shaders/Drift.frag
  ./push.sh assets/shaders/Drift.frag   # Instantly becomes a new active preset!
  ```
* **Persistent Overrides:** Pushed shaders are saved to the app's internal storage (`dataDir`), persisting across app launches and process kills. Reverting (`-r`) removes the override.

---

## Live Code & Plugin Push

GLSL hot-swapping is straightforward because it is text. Java and Kotlin can also be hot-swapped dynamically, subject to Dalvik/ART's runtime rule: **a loaded class cannot be redefined in-place.**

To enable live application logic and UI experimentation, the app separates into a minimal frozen **Host** and a hot-swappable **Plugin** delivered as a compiled `.dex` file:

```bash
./push.sh -p              # Build plugin/ and hot-swap the resulting DEX
./push.sh -p custom.dex   # Push an arbitrary precompiled DEX
./push.sh -c 'status'     # Send arbitrary command text to the running plugin
./push.sh -i              # Query plugin lifecycle status and log output
./push.sh -P              # Detach the active plugin
./push.sh -f data.json    # Push auxiliary files into the plugin's data directory
./push.sh -F              # List uploaded plugin files
```

### The Plugin Contract

The core host API is frozen inside the APK, deliberately compact and resilient to change:

```java
public interface Plugin {
    void attach(Host host);
    void detach();
    boolean event(String name, Object... args);   // resume, pause, back, touch, etc.
    String command(String line);
}
```

By relying on string-dispatched events and named capabilities, plugins can receive new events or host features without requiring APK upgrades. Unknown events simply return `false`.

### Host Capabilities

The `Host` interface provides access to the system:

| Host Method | Purpose |
|---|---|
| `activity()` | Direct escape hatch to `Activity`, window flags, intents, permissions, and resources. |
| `container()` | A full-screen `ViewGroup` overlaid on the render surface, ready for native Android UI. |
| `dataDir()` | Persistent app directory surviving DEX reloads, process death, and APK updates. |
| `state()` | In-memory `Bundle` retained by the host across plugin reloads (preserves state during DEX swaps). |
| `log()` / `toast()` / `post()` | Logging ring buffer, toast notifications, and UI thread dispatch. |
| `extension(name)` | Modular system services (e.g. `extension("gl")` returns the `Gl` interface). |

### Decoupled GL Architecture

The host template (`src/host`) has no dependency on OpenGL. All rendering features are isolated under `src/gl`:
* A UI-only plugin does not touch GL.
* The render loop invokes an optional frame callback registered on `Gl`, running at display refresh rate (60–120Hz) with caught exceptions.
* To convert the project into a pure plugin host without shaders: switch the launcher activity to `.host.HostActivity` and drop `src/gl`.

---

## Fault Tolerance & Resilience

Live reloading in a development environment assumes incoming code may crash, fail, or hang:

* **EGL Context Retention:** `setPreserveEGLContextOnPause(true)` ensures background pushes compile reliably even when the Activity is hidden.
* **Crash Guards:** Every entry point into the plugin is protected with a `Throwable` catch. If user code throws, it is logged and immediately detached mid-frame without taking down the render thread or app process.
* **Armed Launch Markers:** To avoid persistent crash loops from buggy plugins on startup, initialization writes an "arming marker" to disk. If the app crashes before completing its first post-attach frame, the marker remains present on restart, signaling the host to bypass the broken plugin.
* **Loopback Security & Routing:** The HTTP control plane binds strictly to `127.0.0.1` (avoiding IPv6 dual-stack resolution conflicts) on ports starting at 8777. Only local processes on the phone can connect.

---

## HTTP Control API Reference

The internal HTTP server (`PushServer`) exposes the following endpoints on `127.0.0.1:8777+`:

| Endpoint | Method | Description |
|---|---|---|
| `/health` | `GET` | System status, preamble line count, loaded preset inventory. |
| `/presets` | `GET` | List all presets in cycle order (`*` denotes active). |
| `/preset/<name>` | `POST` | Push fragment shader source; compiles and switches view. |
| `/preset/<name>` | `DELETE` | Remove override and revert `<name>` to built-in. |
| `/select/<name>` | `POST` | Switch active preset by name or index. |
| `/plugin` | `GET` | Inspect active plugin status and recent log messages. |
| `/plugin` | `POST` | Push a compiled `.dex` file (`X-Plugin-Class` optional). |
| `/plugin` | `DELETE` | Detach active plugin. |
| `/command` | `POST` | Send an arbitrary string command payload to `plugin.command()`. |
| `/files` | `GET` | List files stored in the plugin's private directory. |
| `/file/<name>` | `POST` | Upload an asset/file directly to the plugin storage. |
| `/file/<name>` | `DELETE` | Delete a stored plugin asset. |

---

## Building & Deploying

The project builds entirely with shell scripts driving raw SDK tools:

```
aapt ──► javac ──► d8 ──► zipalign ──► apksigner
```

Pre-flight validation runs every shader through `glslangValidator` (if present) before packaging.

### Building on the Device (PRoot Ubuntu under Termux)

Because Debian maintains aarch64 native packages for `android-sdk` (`aapt`, `zipalign`, `apksigner`), the entire pipeline can run directly on an Android device:

```bash
# Inside Termux
proot-distro login ubuntu

# Initial SDK setup (downloads platform jar & build tools)
cd /mnt/shaderapp && ./tools/setup-sdk.sh

# Build APK
./build.sh
```

> **Tooling Note:** Build tools use `d8` from build-tools 37 running on Java 17 bytecode. (Earlier versions such as 34 exhibit NPEs under newer JDKs).

### Installation & Deployment

Since shaders and plugins push over the air, APK deployment is only required when the Java host or manifest changes:

```bash
./deploy.sh
```

`deploy.sh` automatically installs via ADB (over local wireless debugging `127.0.0.1`) if connected, or triggers the Android system package installer intent for a single-tap install.

---

## Repository Structure

```
├── AndroidManifest.xml       # App declarations & pre-wired lifecycle services
├── assets/
│   └── shaders/              # Shaders (.frag), shared preamble (_head.glsl), order.txt
├── build.sh                  # Raw toolchain build script
├── build-plugin.sh           # Standalone plugin DEX compilation script
├── deploy.sh                 # ADB / Package installer deploy wrapper
├── docs/                     # Architecture & profiling notes
│   ├── gl-multipass.md       # Multi-pass design notes
│   └── gpu-profiling.md      # GPU counter and performance analysis
├── plugin/                   # Sample hot-swappable Kotlin/Java plugin
├── push.sh                   # CLI tool for live shader/DEX push over loopback
├── src/
│   ├── gl/                   # GLES 2.0 rendering engine & shaders module
│   └── host/                 # Generic plugin container & PushServer host
└── tools/
    └── setup-sdk.sh          # Android SDK bootstrap script for ARM Linux
```

---

## License & Author

Created by [Aarstad](https://github.com/Aarstad). Distributed under the project's open source terms.
