package dev.aarstad.shader.gl;

/**
 * The "gl" host extension, from Host.extension("gl"). Kept out of Plugin so a
 * plugin that only draws views never sees it.
 */
public interface Gl {

    /** Called on the GL thread each frame, with the drawing program bound. */
    interface FrameCallback {
        void frame(float seconds);
    }

    /** Register a per-frame callback, or null to stop. */
    void onFrame(FrameCallback callback);

    /** 1 to 4 floats. Frame-callback only; a name the shader lacks is ignored. */
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
