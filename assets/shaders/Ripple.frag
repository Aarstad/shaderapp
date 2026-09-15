// Radial ripple, damped with distance so the centre stays bright.
void main() {
    vec2 uv = centred();
    float r = length(uv);
    float t = u_time * 1.2;
    float w = sin(r * 14.0 - t) * exp(-r * 1.2);
    vec3 col = palette(w * 3.0 + r * 2.0 + t * 0.2);
    col *= 0.35 + 0.65 * smoothstep(-0.2, 0.6, w + 0.3);
    col *= smoothstep(1.7, 0.2, r);
    gl_FragColor = vec4(col, 1.0);
}
