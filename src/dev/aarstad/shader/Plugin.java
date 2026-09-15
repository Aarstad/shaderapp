package dev.aarstad.shader;

/**
 * The contract between the app and Java pushed into it at runtime.
 *
 * Shaders hot-swap because GLSL is text the driver compiles on demand. Java
 * can hot-swap too -- a dex file loaded through DexClassLoader -- but with one
 * hard limit: a class already loaded can never be replaced. Only *new* classes
 * from a *new* loader come in. So the app is split in two. Everything that has
 * to stay put lives in the APK, and everything worth iterating on lives behind
 * this interface, in a dex that gets pushed.
 *
 * Which makes this interface the one thing a push genuinely cannot change.
 * Adding a method here means a reinstall, so it is deliberately small and
 * deliberately loose -- setUniform() takes any name and any arity, and
 * command() takes free text, precisely so the interesting decisions can be
 * made on the far side of it.
 *
 * A plugin is a class named dev.aarstad.shader.plugin.Main with a no-argument
 * constructor. Anything it throws is caught: the plugin is detached and the
 * app carries on with its built-in behaviour, so a bad push degrades rather
 * than crashes.
 */
public interface Plugin {

    /** What a plugin is allowed to ask of the running app. */
    interface Host {

        /**
         * Set a uniform on the shader that is about to draw. Takes 1 to 4
         * floats, mapping onto float/vec2/vec3/vec4.
         *
         * A name the current shader does not declare is silently ignored --
         * that is ordinary GLSL behaviour, and it is what lets one plugin feed
         * uniforms to a whole cycle of shaders that each use a different
         * subset.
         */
        void setUniform(String name, float... values);

        /** setUniform for the common case, without the array allocation. */
        void setFloat(String name, float value);

        int presetCount();
        String presetName(int index);
        int currentPreset();

        /** Switch preset. Safe to call from frame(). */
        void select(int index);

        /** Briefly show text on screen. */
        void toast(String message);

        /** Append to the app's log ring, which push.sh can read back. */
        void log(String message);

        /** Seconds since the surface was created -- the same clock as u_time. */
        float seconds();
    }

    /** Called once, on the GL thread, right after the dex is loaded. */
    void attach(Host host);

    /**
     * Called on the GL thread every frame, after the current shader's program
     * is bound and before it draws -- so setUniform() lands on the right
     * program.
     *
     * @param seconds the same clock the shaders see as u_time
     */
    void frame(float seconds);

    /**
     * A touch, on the UI thread.
     *
     * x and y are in shader space, not pixels: centred on the screen and
     * aspect-corrected to [-1,1], exactly what centred() returns in GLSL. So a
     * touch coordinate can be handed straight to a shader as a uniform and
     * lands under the finger.
     *
     * @param action a MotionEvent action constant
     * @return true if handled; false lets the built-in tap-to-cycle run
     */
    boolean touch(int action, float x, float y);

    /**
     * Free text from push.sh -c, answered synchronously. The reply goes back
     * over HTTP. This is the escape hatch: a plugin can expose whatever
     * controls it likes without the app knowing anything about them.
     */
    String command(String line);

    /** Called before the plugin is dropped, on the GL thread. */
    void detach();
}
