package dev.aarstad.shader;

/**
 * The "gl" host extension -- what a plugin gets from
 * {@link Plugin.Host#extension} when the app is drawing shaders.
 *
 * Kept out of {@link Plugin} on purpose. A plugin that only wants to put views
 * on screen should not have to know this exists, and a future host that draws
 * something else entirely can offer a different extension without the shared
 * interface changing.
 *
 * Frames are a callback rather than an event because they run 60 times a
 * second, where dispatching on a string and boxing arguments would be waste.
 * The host wraps whatever is registered in the same catch-and-detach guard it
 * uses everywhere else.
 */
public interface Gl {

    /** Called on the GL thread each frame, with the drawing program bound. */
    interface FrameCallback {
        /** @param seconds the same clock the shaders see as u_time */
        void frame(float seconds);
    }

    /**
     * Register a per-frame callback, or null to stop. Cleared automatically
     * when the plugin detaches.
     */
    void onFrame(FrameCallback callback);

    /**
     * Set a uniform on the shader about to draw; 1 to 4 floats map onto
     * float/vec2/vec3/vec4. Only legal from inside a frame callback.
     *
     * A name the current shader doesn't declare is silently ignored -- ordinary
     * GLSL behaviour, and what lets one plugin feed a whole cycle of shaders
     * that each use a different subset.
     */
    void setUniform(String name, float... values);

    /** setUniform for the common case, without the array allocation. */
    void setFloat(String name, float value);

    int presetCount();
    String presetName(int index);
    int currentPreset();
    void select(int index);

    /** Seconds since the surface was created -- the same clock as u_time. */
    float seconds();
}
