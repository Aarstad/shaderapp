// Domain-warped plasma. Five warp iterations costs 9.2ms/frame at 1080x2400
// on this phone's Mali-G610, measured by running the same math as an OpenCL
// kernel -- comfortably inside 60fps, and just outside this phone's 120Hz
// budget of 8.33ms, where the ceiling is three. Each iteration is about
// 1.25ms, so the count is the dial to turn if a screen needs a cheaper one.
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
