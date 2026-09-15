// Follows the plugin's u_touch / u_pulse uniforms. With no plugin loaded they
// stay at zero, which puts the focus at the centre of the screen and the flare
// at nothing -- so this still draws something sensible on its own.
uniform vec2 u_touch;
uniform float u_pulse;

void main() {
    vec2 uv = centred();
    vec2 d = uv - u_touch;
    float r = length(d);
    float t = u_time;

    float ring = sin(r * 18.0 - t * 4.0) * exp(-r * 2.0);
    vec3 col = palette(ring * 2.0 + r * 1.5 + t * 0.2);
    col *= 0.3 + 0.7 * smoothstep(-0.3, 0.7, ring + 0.4);

    // Tap flare, sharpest right under the finger.
    col += u_pulse * exp(-r * r * 6.0) * vec3(1.0, 0.85, 0.6);

    col *= smoothstep(1.9, 0.1, length(uv));
    gl_FragColor = vec4(col, 1.0);
}
