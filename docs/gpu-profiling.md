# Profiling a preset on the real GPU

Status: **working**, and how the numbers in the shader comments were obtained.

There is no frame timer in the app. What there is instead is the phone's own
GPU, reachable from Termux, so a preset's cost can be measured directly by
running the same arithmetic as an OpenCL kernel over the same pixel count.

## Why this is possible at all

The phone is a mid-range MediaTek with a Mali-G610 MC2, and its OpenCL driver
sits in `/vendor/lib64/egl/`. Android's linker refuses that path to an app
namespace, so the obvious routes all fail:

- `/vendor/lib64/libOpenCL.so` is whitelisted and loads, but it is only an ICD
  *loader*. It returns `-1001 CL_PLATFORM_NOT_FOUND` because its own dlopen of
  the driver hits the same wall. No environment variable gets around it.
- `libvulkan.so` enumerates a device, but on a stock Termux that device is
  llvmpipe, a CPU rasterizer. It is easy to mistake for success -- check
  `deviceType` and `deviceName` before believing a Vulkan number.

`/data` *is* on the linker's permitted list and the driver blob is world
readable, so copying it and its dependency closure into `~/gpu/min` and loading
it from there works. The blob exports the whole `cl*` API itself, so the ICD
loader is skipped. One trap: there are two different `libgralloctypes.so`, and
the `/vendor` one is missing symbols `libui.so` needs -- take the `/system/lib64`
one.

`~/gpu/bin/gpuenv <program>` sets the library path. There is no import library,
so every entry point comes out of `dlsym`.

## What it measures, and what it does not

A GLSL preset and an OpenCL kernel doing the same arithmetic over the same
pixels are not the same program: different compilers, different scheduling, no
rasteriser or framebuffer traffic in the OpenCL case. `_head.glsl` is
`highp float`, which matches fp32, so the arithmetic is comparable, but treat
an absolute number as carrying a factor of roughly two either way.

What survives that is the shape: which preset is expensive, which line in it is
expensive, and whether a change helps. Those held up every time they were
checked against the picture.

Two things will quietly ruin a measurement:

- **Close the app first.** It renders fullscreen on the same GPU. Most presets
  shift under 3% but Electric read 25.5ms with the app up against 19.8ms
  without -- enough to change what you conclude. `./push.sh -l` saying the app
  is not listening is the check.
- **Know the panel.** This phone is 120Hz, so the budget is 8.33ms, not 16.67.
  Assuming 60 makes nearly every preset look comfortable when most miss.

## What it found

At 1080x2400, idle, against 8.33ms:

| preset | ms | fits 120Hz |
|---|---|---|
| Tunnel | 5.2 | yes |
| Plasma, 5 warps | 9.2 | no |
| Metaballs | 10.0 | no |
| Electric | 17.2 | no |
| Voronoi, before | 33.7 | no |
| Voronoi, after | 17.9 | no |

Tunnel is the only preset that holds 120Hz. Two changes came out of this and
are in the shaders now: Voronoi comparing squared distances rather than calling
`length()` nine times per pixel, which is 1.89x for a picture that is
bit-identical, and Electric's shimmer becoming a stepped hash rather than a
fourth `noise()` call.

Cost is linear in pixel count, so the lever that keeps a preset's look intact is
resolution rather than cutting octaves: at 67% scale Electric reaches 7.65ms and
Voronoi 8.01ms, both inside 120Hz. That needs an offscreen target and a blit --
the FBO work in `gl-multipass.md`, still unwritten.

Measuring `noise()` on its own by sweeping the call count put it at 3.3ms a
call, linear, which is what makes Electric's budget arithmetic so unforgiving:
five calls is 16.4ms of its 17.2ms and everything else in the shader is noise
by comparison. Three things that looked like they should help and did not,
recorded so nobody spends the afternoon again: vectorising the four corner
hashes into float4 lanes (0.96x, slightly worse -- the Mali compiler already
does it), `native_divide` on the glow reciprocals with `native_sin` (1.02x),
and replacing the `/256.0` wrap with a reciprocal multiply (1.00x).

## What this cannot become

OpenCL cannot draw these presets. Sharing a texture with GL needs an EGL context
in the app's own process, and this driver is only reachable from Termux via the
`/data` copy above. Compute in one process and display in another do not meet.
It is a measuring instrument, not a renderer.
