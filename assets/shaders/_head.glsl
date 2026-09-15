precision highp float;
uniform vec2 u_res;
uniform float u_time;
vec2 centred() {
    return (gl_FragCoord.xy * 2.0 - u_res) / min(u_res.x, u_res.y);
}
vec3 palette(float x) {
    return 0.5 + 0.5 * cos(vec3(0.0, 2.1, 4.2) + x);
}
