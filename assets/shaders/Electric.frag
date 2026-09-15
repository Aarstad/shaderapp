// Filaments from warped value noise, glowing as 1/|d| so they stay hair-thin.
//
// The bolt snaps on a quantised clock -- that stepping is what reads as
// electric rather than liquid. Everything else runs on continuous time, so the
// image is never actually still; with the whole shader on the snapped clock it
// looks like a low frame rate no matter how fast the GPU is going.
//
// Stays highp on purpose: at mediump, u_time is too coarse to quantise once the
// app has been running a few minutes, and the bolt stops moving entirely.
uniform vec2 u_touch;
uniform float u_pulse;

// No sin: a couple of fracts and a dot are far cheaper on a mobile GPU.
float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

// The lattice is wrapped to 256 cells before hashing. That hash loses
// precision badly on large inputs, and the time offset grows without bound, so
// without this the noise collapses into flat facets after a few minutes of
// uptime. 256 cells is far more than reaches the screen, so the tiling is
// invisible.
float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    vec2 i0 = i - floor(i / 256.0) * 256.0;
    vec2 i1 = i0 + 1.0;
    i1 = i1 - floor(i1 / 256.0) * 256.0;
    return mix(mix(hash(i0),                  hash(vec2(i1.x, i0.y)), f.x),
               mix(hash(vec2(i0.x, i1.y)),    hash(i1),               f.x), f.y);
}

void main() {
    vec2 uv = centred();
    vec2 p = uv - u_touch;
    float r = length(p);

    // Noise is sampled on a ring rather than against atan() directly. The angle
    // jumps from +pi to -pi along one ray, and feeding that to noise tears the
    // field along a visible radial seam; cos/sin wrap, so this does not.
    vec2 ring = p / max(r, 0.0001);

    // Wrapped, so the noise coordinate never grows large enough to lose
    // precision. The seam lands on a snap boundary and is lost in the flicker.
    float t = u_time - floor(u_time / 128.0) * 128.0;
    float snap = floor(t * 24.0) / 24.0;
    float flicker = 0.7 + 0.3 * hash(vec2(snap, 3.7));

    // Bolt: snapped, three octaves.
    // Direction and time only. Letting r in here made the target radius vary
    // with distance too, and abs(r - target) then folded into petals rather
    // than tracing one ragged loop.
    vec2 q = ring * 2.4 + vec2(0.0, -snap * 2.0);
    float warp = 0.5 * noise(q) + 0.25 * noise(q * 2.02) + 0.125 * noise(q * 4.03);

    float d = abs(r - 0.34 - 0.30 * (warp * 2.0 - 0.875));
    float core = 0.006 / (d + 0.004);

    // Glow breathes continuously, so the bolt keeps moving between snaps.
    float breathe = 0.85 + 0.15 * sin(t * 2.3);
    float glow = 0.09 / (d + 0.09) * breathe;

    // Crackle runs on continuous time and drifts the whole while.
    float n2 = noise(ring * 5.0 + vec2(5.0, r * 6.0 + t * 2.5));
    float d2 = abs(r - 0.62 - 0.30 * (n2 * 2.0 - 1.0));
    float crackle = 0.004 / (d2 + 0.007);

    // A slow continuous shimmer over the whole field.
    float shimmer = 0.9 + 0.1 * noise(ring * 3.0 + vec2(t * 1.5, 0.0));

    vec3 cold = vec3(0.25, 0.55, 1.0);
    vec3 hot  = vec3(0.85, 0.95, 1.0);

    vec3 col = hot * core * flicker
             + cold * glow * 0.8
             + mix(cold, hot, 0.5) * crackle;

    col *= shimmer;
    col += u_pulse * exp(-r * r * 5.0) * vec3(0.6, 0.8, 1.0);
    col *= smoothstep(1.9, 0.15, length(uv));
    gl_FragColor = vec4(col, 1.0);
}
