// Animated Voronoi. Tracking the two nearest seeds and shading on their
// difference draws the cell borders; d1 alone would only give blobs.
vec2 hash2(vec2 p) {
    p = vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)));
    return fract(sin(p) * 43758.5453);
}
void main() {
    vec2 g = centred() * 3.0;
    vec2 cell = floor(g);
    vec2 f = fract(g);
    float t = u_time * 0.6;
    float d1 = 8.0;
    float d2 = 8.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 o = vec2(float(x), float(y));
            vec2 h = hash2(cell + o);
            vec2 seed = o + 0.5 + 0.5 * sin(t + 6.2831853 * h);
            float d = length(seed - f);
            if (d < d1) { d2 = d1; d1 = d; }
            else if (d < d2) { d2 = d; }
        }
    }
    float edge = smoothstep(0.0, 0.25, d2 - d1);
    vec3 col = palette(d1 * 2.5 + t * 0.5);
    col *= 0.25 + 0.75 * edge;
    gl_FragColor = vec4(col, 1.0);
}
