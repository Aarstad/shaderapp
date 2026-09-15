package dev.aarstad.shader;

/**
 * Fragment shaders, one per preset. Every one is GLSL ES 1.00 and takes the
 * same uniforms -- u_res (pixels) and u_time (seconds) -- so the renderer can
 * swap between them without special-casing.
 *
 * Budget: these target a mid-range Mali at 1080p/60. Loop counts are the knob
 * to turn first if a preset drops frames.
 */
final class Presets {

    private Presets() {}

    /** Shared preamble: uniforms plus a centred, aspect-corrected uv in [-1,1]. */
    private static final String HEAD =
        "precision highp float;\n" +
        "uniform vec2 u_res;\n" +
        "uniform float u_time;\n" +
        "vec2 centred() {\n" +
        "    return (gl_FragCoord.xy * 2.0 - u_res) / min(u_res.x, u_res.y);\n" +
        "}\n" +
        "vec3 palette(float x) {\n" +
        "    return 0.5 + 0.5 * cos(vec3(0.0, 2.1, 4.2) + x);\n" +
        "}\n";

    // Domain-warped plasma. Five warp iterations is about the most a mid-range
    // Mali will hold at 60fps on a 1080p-class screen.
    private static final String PLASMA = HEAD +
        "void main() {\n" +
        "    vec2 uv = centred();\n" +
        "    float t = u_time * 0.35;\n" +
        "    vec2 p = uv;\n" +
        "    for (int i = 0; i < 5; i++) {\n" +
        "        p += vec2(sin(p.y * 3.0 + t), cos(p.x * 3.0 - t)) * 0.25;\n" +
        "    }\n" +
        "    float d = length(p);\n" +
        "    vec3 col = palette(d * 3.0 + t);\n" +
        "    col *= 1.0 - 0.35 * length(uv);\n" +
        "    gl_FragColor = vec4(col, 1.0);\n" +
        "}\n";

    // Perspective tunnel. Depth goes as 1/r, which is what makes the rings
    // bunch up towards the centre; the max() keeps the axis from exploding.
    private static final String TUNNEL = HEAD +
        "void main() {\n" +
        "    vec2 uv = centred();\n" +
        "    float r = length(uv);\n" +
        "    float a = atan(uv.y, uv.x);\n" +
        "    float depth = 0.35 / max(r, 0.04) + u_time * 0.6;\n" +
        "    float rings  = sin(depth * 6.2831853);\n" +
        "    float spokes = sin(a * 6.0 + depth * 1.5);\n" +
        "    float grid = smoothstep(0.0, 0.6, rings * spokes);\n" +
        "    vec3 col = palette(depth * 0.8);\n" +
        "    col *= 0.25 + 0.75 * grid;\n" +
        "    col *= smoothstep(1.5, 0.15, r);\n" +
        "    gl_FragColor = vec4(col, 1.0);\n" +
        "}\n";

    // Six-fold mirror over an inversion fold. Folding the angle before the
    // fractal is what gives it the kaleidoscope symmetry rather than noise.
    private static final String KALEIDOSCOPE = HEAD +
        "void main() {\n" +
        "    vec2 uv = centred();\n" +
        "    float r = length(uv);\n" +
        "    float a = atan(uv.y, uv.x);\n" +
        "    float seg = 6.2831853 / 6.0;\n" +
        "    a = abs(mod(a, seg) - seg * 0.5);\n" +
        "    float t = u_time * 0.5;\n" +
        "    vec2 p = vec2(cos(a), sin(a)) * r * 2.2 - 0.7;\n" +
        "    float v = 0.0;\n" +
        "    for (int i = 0; i < 4; i++) {\n" +
        "        p = abs(p) / max(dot(p, p), 0.001) - (0.7 + 0.15 * sin(t));\n" +
        "        v += exp(-3.0 * abs(p.x - p.y));\n" +
        "    }\n" +
        "    vec3 col = palette(v * 1.5 + t);\n" +
        "    col *= 0.4 + 0.6 * smoothstep(1.8, 0.2, r);\n" +
        "    gl_FragColor = vec4(col, 1.0);\n" +
        "}\n";

    // Four metaballs summed as an inverse-distance field, thresholded with a
    // smoothstep so they fuse instead of overlapping as discs.
    private static final String METABALLS = HEAD +
        "float ball(vec2 uv, vec2 c, float r) {\n" +
        "    return r / max(length(uv - c), 0.001);\n" +
        "}\n" +
        "void main() {\n" +
        "    vec2 uv = centred();\n" +
        "    float t = u_time * 0.7;\n" +
        "    float f = 0.0;\n" +
        "    f += ball(uv, vec2(sin(t * 1.1), cos(t * 0.9)) * 0.55, 0.30);\n" +
        "    f += ball(uv, vec2(cos(t * 0.7), sin(t * 1.3)) * 0.60, 0.26);\n" +
        "    f += ball(uv, vec2(sin(t * 0.5 + 2.0), sin(t * 0.8)) * 0.50, 0.22);\n" +
        "    f += ball(uv, vec2(cos(t * 1.5), cos(t * 0.4 + 1.0)) * 0.45, 0.18);\n" +
        "    float m = smoothstep(1.6, 2.6, f);\n" +
        "    vec3 col = mix(vec3(0.03, 0.04, 0.09), palette(f * 0.9 + t * 0.4), m);\n" +
        "    col += 0.15 * smoothstep(1.2, 2.0, f);\n" +
        "    gl_FragColor = vec4(col, 1.0);\n" +
        "}\n";

    // Animated Voronoi. Tracking the two nearest seeds and shading on their
    // difference draws the cell borders; d1 alone would only give blobs.
    private static final String VORONOI = HEAD +
        "vec2 hash2(vec2 p) {\n" +
        "    p = vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)));\n" +
        "    return fract(sin(p) * 43758.5453);\n" +
        "}\n" +
        "void main() {\n" +
        "    vec2 g = centred() * 3.0;\n" +
        "    vec2 cell = floor(g);\n" +
        "    vec2 f = fract(g);\n" +
        "    float t = u_time * 0.6;\n" +
        "    float d1 = 8.0;\n" +
        "    float d2 = 8.0;\n" +
        "    for (int y = -1; y <= 1; y++) {\n" +
        "        for (int x = -1; x <= 1; x++) {\n" +
        "            vec2 o = vec2(float(x), float(y));\n" +
        "            vec2 h = hash2(cell + o);\n" +
        "            vec2 seed = o + 0.5 + 0.5 * sin(t + 6.2831853 * h);\n" +
        "            float d = length(seed - f);\n" +
        "            if (d < d1) { d2 = d1; d1 = d; }\n" +
        "            else if (d < d2) { d2 = d; }\n" +
        "        }\n" +
        "    }\n" +
        "    float edge = smoothstep(0.0, 0.25, d2 - d1);\n" +
        "    vec3 col = palette(d1 * 2.5 + t * 0.5);\n" +
        "    col *= 0.25 + 0.75 * edge;\n" +
        "    gl_FragColor = vec4(col, 1.0);\n" +
        "}\n";

    /** Cycle order. NAMES and SOURCES are parallel arrays. */
    static final String[] NAMES = {
        "Plasma", "Tunnel", "Kaleidoscope", "Metaballs", "Voronoi",
    };

    static final String[] SOURCES = {
        PLASMA, TUNNEL, KALEIDOSCOPE, METABALLS, VORONOI,
    };
}
