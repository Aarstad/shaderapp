// Filaments from warped value noise, glowing as 1/|d| so they stay hair-thin.
//
// The bolt used to snap to a quantised clock, on the theory that the stepping
// read as electric rather than liquid. It does not: at 24 steps a second
// against this phone's 120Hz panel every position is held for five frames, and
// the result reads as congestion. The bolt now runs on continuous time and
// only the brightness flicker still steps. Snapping costs nothing either way
// (20.2ms against 20.4ms), so this is a look decision, not a speed one.
//
// Stays highp on purpose: at mediump, u_time is too coarse to step the flicker
// once the app has been running a few minutes, and it stops moving entirely.
//
// Cost is all in noise(): 3.3ms per call at 1080x2400 on this phone's
// Mali-G610, linear in the call count, which is why the shimmer below is a
// stepped hash rather than a fifth call. Four calls still only reaches 60fps
// -- 120Hz would need roughly one, which is not this shader. Rendering to a
// smaller buffer is the way in, and needs the FBO work in docs/gl-multipass.md.
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
    // precision. The seam lands mid-flicker and is not visible.
    float t = u_time - floor(u_time / 128.0) * 128.0;

    // Brightness still steps -- it is a flat multiplier, so stepping it reads
    // as a flicker rather than as a stutter in the geometry.
    float flicker = 0.7 + 0.3 * hash(vec2(floor(t * 24.0), 3.7));

    // Bolt: three octaves on continuous time.
    // Direction and time only. Letting r in here made the target radius vary
    // with distance too, and abs(r - target) then folded into petals rather
    // than tracing one ragged loop.
    vec2 q = ring * 2.4 + vec2(0.0, -t * 2.0);
    float warp = 0.5 * noise(q) + 0.25 * noise(q * 2.02) + 0.125 * noise(q * 4.03);

    float d = abs(r - 0.34 - 0.30 * (warp * 2.0 - 0.875));
    float core = 0.006 / (d + 0.004);

    // Glow breathes continuously.
    float breathe = 0.85 + 0.15 * sin(t * 2.3);
    float glow = 0.09 / (d + 0.09) * breathe;

    // Crackle runs on continuous time and drifts the whole while.
    float n2 = noise(ring * 5.0 + vec2(5.0, r * 6.0 + t * 2.5));
    float d2 = abs(r - 0.62 - 0.30 * (n2 * 2.0 - 1.0));
    float crackle = 0.004 / (d2 + 0.007);

    // A slow shimmer over the whole field. Stepped on its own slower clock
    // rather than sampled from noise: it is a flat multiplier, so the spatial
    // variation a fourth noise() call would buy is not worth 3.3ms.
    float shimmer = 0.9 + 0.1 * hash(vec2(floor(t * 8.0), 1.3));

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
