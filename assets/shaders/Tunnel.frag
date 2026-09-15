// Perspective tunnel. Depth goes as 1/r, which is what makes the rings
// bunch up towards the centre; the max() keeps the axis from exploding.
void main() {
    vec2 uv = centred();
    float r = length(uv);
    float a = atan(uv.y, uv.x);
    float depth = 0.35 / max(r, 0.04) + u_time * 0.6;
    float rings  = sin(depth * 6.2831853);
    float spokes = sin(a * 6.0 + depth * 1.5);
    float grid = smoothstep(0.0, 0.6, rings * spokes);
    vec3 col = palette(depth * 0.8);
    col *= 0.25 + 0.75 * grid;
    col *= smoothstep(1.5, 0.15, r);
    gl_FragColor = vec4(col, 1.0);
}
