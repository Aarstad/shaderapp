// Domain-warped plasma. Five warp iterations is about the most a mid-range
// Mali will hold at 60fps on a 1080p-class screen.
void main() {
    vec2 uv = centred();
    float t = u_time * 0.35;
    vec2 p = uv;
    for (int i = 0; i < 5; i++) {
        p += vec2(sin(p.y * 3.0 + t), cos(p.x * 3.0 - t)) * 0.25;
    }
    float d = length(p);
    vec3 col = palette(d * 3.0 + t);
    col *= 1.0 - 0.35 * length(uv);
    gl_FragColor = vec4(col, 1.0);
}
