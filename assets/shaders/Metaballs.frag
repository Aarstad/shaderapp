// Four metaballs summed as an inverse-distance field, thresholded with a
// smoothstep so they fuse instead of overlapping as discs.
float ball(vec2 uv, vec2 c, float r) {
    return r / max(length(uv - c), 0.001);
}
void main() {
    vec2 uv = centred();
    float t = u_time * 0.7;
    float f = 0.0;
    f += ball(uv, vec2(sin(t * 1.1), cos(t * 0.9)) * 0.55, 0.30);
    f += ball(uv, vec2(cos(t * 0.7), sin(t * 1.3)) * 0.60, 0.26);
    f += ball(uv, vec2(sin(t * 0.5 + 2.0), sin(t * 0.8)) * 0.50, 0.22);
    f += ball(uv, vec2(cos(t * 1.5), cos(t * 0.4 + 1.0)) * 0.45, 0.18);
    float m = smoothstep(1.6, 2.6, f);
    vec3 col = mix(vec3(0.03, 0.04, 0.09), palette(f * 0.9 + t * 0.4), m);
    col += 0.15 * smoothstep(1.2, 2.0, f);
    gl_FragColor = vec4(col, 1.0);
}
