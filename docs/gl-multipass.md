# Target: multi-pass GL for plugins

Status: **not implemented.** This is a design decision written down before the
work, so whoever picks it up does not have to rediscover the reasoning. It
assumes no knowledge of the discussion that produced it.

## What this app is, in one paragraph

`shader` is an Android app with no Gradle and no plugin marketplace: one APK
that acts as a *host* for code pushed into it at runtime over a loopback
socket. `push.sh` sends either a fragment shader (recompiled on the GL thread,
picture changes under you) or a `.dex` (loaded, attached, and running without
the app restarting). The host half is `src/host/` — `Plugin`, `PluginLoader`,
`PushServer`, `HostActivity` — and knows nothing about graphics. The GL half is
`src/gl/` — `ShaderActivity` extends `HostActivity` and supplies a
`GLSurfaceView` as its background, plus `PresetRenderer`, which owns all the GL.
Read `README.md` first; the sections *Live code push*, *The contract* and
*Not shader-shaped* cover the parts that matter here.

## How a plugin reaches GL today

`Plugin` (the interface every pushed dex implements) has no graphics in it at
all. Plugins ask for capabilities by name:

```java
Gl gl = (Gl) host.extension("gl");   // null in a host that draws nothing
```

`ShaderActivity.extension()` answers `"gl"` with an implementation of `Gl`
(`src/gl/.../Gl.java`). That interface is deliberately small: register a
per-frame callback, write uniforms, query and switch presets, read the clock.
It hands out no GL handles and no context.

The payoff is that hot swaps are trivially safe. `PresetRenderer` is the sole
owner of the EGL context, the programs and the vertex buffer, so a plugin
swap cannot leak a GPU object or leave a dangling handle — the plugin had
none to begin with. A swap is: drop the old plugin on the UI thread, null the
frame callback, let the old `DexClassLoader` become garbage. The GL thread
never participates beyond one volatile read per frame.

## The limit we have hit

`PresetRenderer.onDrawFrame` is a single hardcoded pass:

```
glClear -> glUseProgram(preset) -> frame callback -> glDrawArrays(TRIANGLES, 0, 3)
```

straight to the default framebuffer. There is no FBO anywhere in the file, and
`assets/shaders/_head.glsl` — the preamble prepended to every preset — declares
only `u_res` and `u_time`. No sampler, no second render target.

So anything needing more than one pass over a fullscreen triangle is not
awkward, it is unreachable: ping-pong feedback, separable blur, reaction-
diffusion, fluid, custom textures and LUTs, anything reading its own previous
frame.

## The decision

**Add exactly two methods to `Gl`, once, and build everything else plugin-side.**

```java
/** Run raw GLES on the render thread, between frames. */
void runOnGlThread(Runnable task);

/** Bind a texture to a sampler uniform on the program drawing this frame. */
void setSampler(String name, int texture);
```

### Why not a declarative render graph, or a resource-token registry

Both are better-behaved designs in the abstract — the host keeps ownership of
every FBO and texture, and a swap can free a plugin's allocations
automatically. They were considered and rejected **for now**, for one reason:

*`Gl` is frozen at install, exactly like `Plugin` is.*

A pushed dex resolves `Plugin`, `Plugin.Host` and `Gl` through the app's own
classloader — see the comment in `PluginLoader.instantiate`, where the parent
loader is what makes the cast legal across the dex boundary. Any method added
to `Gl` is APK surface. It needs a rebuild, a reinstall and an installer
prompt, which is the precise cost this whole architecture exists to avoid.

A render-graph vocabulary (`pass`, `size`, `bind`, `shader`, ...) or a
resource API (`createTexture`, `createBuffer`, formats, wrap modes, filters)
is not something you can declare up front and be done with. It churns. Every
new pass type or texture format would be another reinstall. `README.md` lists
exactly one thing that still needs a reinstall — the manifest — and argues it
is a templating problem because you *can* declare it all in advance. A DSL is
not like that.

`runOnGlThread` is the only option that is closed under extension: add it once
and every later capability, up to and including a declarative graph layer, is
plugin code that pushes in seconds.

### Why `setSampler` is nevertheless required

It is the one thing plugin-side code provably cannot do for itself. With raw
GL access a plugin can create its own FBOs and render its own passes, but it
cannot feed the result into the host's preset pass: `PresetRenderer` owns the
program object and decides texture-unit assignment. Without `setSampler` you
get a second pipeline that cannot talk to the first, which is no use.

The preset shader declares the sampler itself, e.g. `uniform sampler2D
u_feedback;`, and `PresetRenderer.locate()` already caches uniform locations by
name — `glGetUniformLocation` works the same for samplers, so the existing
lookup path needs no change. `setSampler` must allocate a texture unit,
`glActiveTexture` + `glBindTexture` it, and `glUniform1i` the unit index (not
the texture id — a common bug). Units should be assigned per frame and reset,
since the set of samplers a plugin binds can change between frames.

## Implementation notes

**Run tasks via `view.queueEvent`, never inside the frame callback.** The frame
callback fires with the preset program already bound and `PresetRenderer.drawing`
set to it, so that `setUniform` knows where to write — see the comment
"Built-ins are set first, so a plugin can add uniforms or override them". Raw GL
in that window can leave a different program or framebuffer bound and corrupt
the host's draw. `queueEvent` runs between frames with nothing of the host's
bound, and it is already how `ShaderActivity.onGl()` installs shaders.

**Wrap the task in the same catch-and-detach guard as everything else.** Look at
how `ShaderActivity.glApi.onFrame` wraps the callback: it re-checks
`plugins.active()` and routes a throw to `plugins.failed(...)`. A GL task must
do the same, or a bad push takes the render thread down.

**Tell plugins when the context is recreated.** This is a gap that only opens up
once plugins own GL objects. `PresetRenderer.onSurfaceCreated` clears and
rebuilds every host program — "A fresh context invalidates every program id we
were holding" — but a plugin's textures would die silently with no notification,
and the plugin would go on drawing with dead handles. `ShaderActivity`
`setPreserveEGLContextOnPause(true)` makes this rare, not impossible.

The fix costs nothing, because plugin events are dispatched by string:
fire `plugins.event("gl.surface")` from `onSurfaceCreated`. Adding a *new event
name* needs no interface change at all — `Plugin.event` takes a `String`, and
unknown names must already return `false`, so plugins built before this exists
keep working untouched. The `String` constants on `Plugin` are documentation,
not a registry. (Worth noting in the README: this is a second thing the
string-keyed design buys, beyond the one already described.)

## What gets built on top, plugin-side

None of this belongs in the APK:

- a `GlScope`-style helper that tracks what a plugin allocated and frees it in
  `detach()` and on `gl.surface`
- ping-pong FBO pairs, half-res targets, a blur/feedback kit
- LUT and texture loading
- eventually, if the vocabulary settles, a declarative pass graph

It all lives in the pushed dex, so a bug in any of it is fixed by a push rather
than a reinstall.

## Known risk, accepted

A plugin can now leak GPU memory or crash the render loop, and **nothing will
catch it**. The existing guards do not cover this: the `armed`/`proved()`
watchdog in `PluginLoader` catches a plugin that wedges the UI thread for two
seconds after attach, and the `catch`-and-`fail()` path catches a plugin that
*throws* — but a texture that is allocated and never deleted is neither. The
failure mode is a slow leak until the app is restarted.

This is accepted because this is a hot-reload tool running on its author's own
phone, not a sandbox for untrusted code. Restarting the app costs nothing and
the feedback loop is seconds long.

## Sequencing

1. Add the two `Gl` methods and the `"gl.surface"` event. One reinstall.
2. Build the ping-pong and resource-cleanup helpers as plugin-side library code.
   Write two or three real effects with them — feedback, blur, reaction-diffusion.
3. *Only then*, if the pass vocabulary has actually stabilized, consider
   promoting a graph layer into the host as a separate capability name —
   `host.extension("gl.graph")` — for the ownership guarantees. A new name, not
   new methods on `Gl`.

Step 3 is the one that is hard to walk back, which is why it is last. Freezing
a DSL into the APK before knowing what it needs is the mistake this ordering
exists to avoid.
