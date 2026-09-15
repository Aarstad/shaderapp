// Six-fold mirror over an inversion fold. Folding the angle before the
// fractal is what gives it the kaleidoscope symmetry rather than noise.
void main() {
    vec2 uv = centred();
    float r = length(uv);
    float a = atan(uv.y, uv.x);
    float seg = 6.2831853 / 6.0;
    a = abs(mod(a, seg) - seg * 0.5);
    float t = u_time * 0.5;
    vec2 p = vec2(cos(a), sin(a)) * r * 2.2 - 0.7;
    float v = 0.0;
    for (int i = 0; i < 4; i++) {
        p = abs(p) / max(dot(p, p), 0.001) - (0.7 + 0.15 * sin(t));
        v += exp(-3.0 * abs(p.x - p.y));
    }
    vec3 col = palette(v * 1.5 + t);
    col *= 0.4 + 0.6 * smoothstep(1.8, 0.2, r);
    gl_FragColor = vec4(col, 1.0);
}
