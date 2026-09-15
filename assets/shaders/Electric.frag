// Filaments from warped value noise, glowing as 1/|d| so they stay hair-thin.
// The clock is quantised, so the bolt snaps between shapes instead of sliding.
uniform vec2 u_touch;
uniform float u_pulse;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i),                hash(i + vec2(1.0, 0.0)), f.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

// Three octaves is the frame-rate knob; drop to two if it drags.
float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 3; i++) {
        v += a * noise(p);
        p *= 2.02;
        a *= 0.5;
    }
    return v;
}

void main() {
    vec2 uv = centred();
    vec2 src = u_touch;

    float t = u_time;
    float snap = floor(t * 16.0) / 16.0;
    float flicker = 0.7 + 0.3 * hash(vec2(snap, 3.7));

    vec2 p = uv - src;
    float r = length(p);
    float a = atan(p.y, p.x);

    float warp = fbm(vec2(a * 1.6, r * 3.0 - snap * 2.0) * 1.5);
    float d = abs(r - 0.34 - 0.24 * (warp * 2.0 - 1.0));
    float core = 0.006 / (d + 0.004);
    float glow = 0.09 / (d + 0.09);

    float warp2 = fbm(vec2(a * 3.3 + 5.0, r * 6.0 + snap * 3.0) * 2.0);
    float d2 = abs(r - 0.62 - 0.30 * (warp2 * 2.0 - 1.0));
    float crackle = 0.004 / (d2 + 0.007);

    vec3 cold = vec3(0.25, 0.55, 1.0);
    vec3 hot  = vec3(0.85, 0.95, 1.0);

    vec3 col = hot * core * flicker
             + cold * glow * 0.8
             + mix(cold, hot, 0.5) * crackle * flicker;

    col += u_pulse * exp(-r * r * 5.0) * vec3(0.6, 0.8, 1.0);
    col *= smoothstep(1.9, 0.15, length(uv));
    gl_FragColor = vec4(col, 1.0);
}
