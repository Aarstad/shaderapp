package dev.aarstad.shader.gl;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import dev.aarstad.shader.host.PushServer;

/** Draws one fullscreen triangle per frame and lets the fragment shader do all the work. */
final class PresetRenderer implements GLSurfaceView.Renderer {

    // Fullscreen triangle -- cheaper than a quad and needs no index buffer.
    private static final float[] VERTS = {
        -1.0f, -1.0f,
         3.0f, -1.0f,
        -1.0f,  3.0f,
    };

    private static final String VERTEX_SRC =
        "attribute vec2 a_pos;\n" +
        "void main() {\n" +
        "    gl_Position = vec4(a_pos, 0.0, 1.0);\n" +
        "}\n";

    /** One linked program and its uniform locations. */
    private static final class Prog {
        int program, aPos, uRes, uTime;
        /** Locations of plugin-set uniforms, by name. -1 means absent. */
        final HashMap<String, Integer> locs = new HashMap<>();
    }

    private final String head;

    // Read from the server and UI threads, mutated on the GL thread.
    private final CopyOnWriteArrayList<String> names = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> bodies = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> errors = new CopyOnWriteArrayList<>();

    /** GL thread only, parallel to names/bodies. */
    private final List<Prog> progs = new ArrayList<>();

    private FloatBuffer verts;

    /**
     * The shared vertex shader, kept alive for the life of the context rather
     * than deleted after setup, because a pushed preset needs something to link
     * against later.
     */
    private int vs;

    private volatile int index = 0;
    private volatile Gl.FrameCallback frameCallback;

    /**
     * Whether onSurfaceCreated has run. GLSurfaceView drains its event queue
     * before a surface exists, so a queued compile really does run with no
     * context and every GL call quietly fails.
     */
    private volatile boolean surfaceReady;

    private int width, height;
    private long startMs;

    /** The program bound for this frame -- what setUniform() writes to. */
    private Prog drawing;

    PresetRenderer(String head, List<Presets.Preset> initial) {
        this.head = head;
        for (Presets.Preset p : initial) {
            names.add(p.name);
            bodies.add(p.body);
            errors.add(null);
        }
    }

    void setFrameCallback(Gl.FrameCallback cb) { frameCallback = cb; }

    /** Chosen before the surface exists, so the app reopens where it was left. */
    void setInitialIndex(int i) {
        if (i >= 0 && i < names.size()) index = i;
    }

    List<String> names() { return names; }
    int index() { return index; }
    float seconds() { return (SystemClock.uptimeMillis() - startMs) / 1000.0f; }
    String errorFor(int i) { return i < errors.size() ? errors.get(i) : null; }

    /** Write a uniform on the program that is drawing this frame. */
    void setUniform(String name, float[] v) {
        if (drawing == null || v == null) return;
        int loc = locate(drawing, name);
        if (loc < 0) return;
        switch (v.length) {
            case 1: GLES20.glUniform1f(loc, v[0]); break;
            case 2: GLES20.glUniform2f(loc, v[0], v[1]); break;
            case 3: GLES20.glUniform3f(loc, v[0], v[1], v[2]); break;
            case 4: GLES20.glUniform4f(loc, v[0], v[1], v[2], v[3]); break;
            default: break;
        }
    }

    void setFloat(String name, float value) {
        if (drawing == null) return;
        int loc = locate(drawing, name);
        if (loc >= 0) GLES20.glUniform1f(loc, value);
    }

    private int locate(Prog p, String name) {
        Integer cached = p.locs.get(name);
        if (cached != null) return cached;
        int loc = GLES20.glGetUniformLocation(p.program, name);
        p.locs.put(name, loc);
        return loc;
    }

    void select(int i) {
        if (i < 0 || i >= progs.size()) return;
        index = i;
        // u_res is per-program state and this one may never have been sized.
        if (width > 0) applyResolution(i);
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        ByteBuffer bb = ByteBuffer.allocateDirect(VERTS.length * 4);
        bb.order(ByteOrder.nativeOrder());
        verts = bb.asFloatBuffer();
        verts.put(VERTS).position(0);

        // A fresh context invalidates every program id we were holding.
        progs.clear();
        vs = compileOrThrow(GLES20.GL_VERTEX_SHADER, VERTEX_SRC);

        for (int i = 0; i < bodies.size(); i++) {
            Prog p = build(bodies.get(i));
            if (p == null) {
                // A persisted shader that no longer compiles -- on this driver, or after an edit saved before a context loss.
                errors.set(i, lastError);
                p = build(Presets.FALLBACK_BODY);
            }
            progs.add(p);
        }
        if (index >= progs.size()) index = 0;
        startMs = SystemClock.uptimeMillis();
        surfaceReady = true;
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        GLES20.glViewport(0, 0, w, h);
        width = w;
        height = h;
        for (int i = 0; i < progs.size(); i++) applyResolution(i);
    }

    private void applyResolution(int i) {
        Prog p = progs.get(i);
        GLES20.glUseProgram(p.program);
        GLES20.glUniform2f(p.uRes, (float) width, (float) height);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        int i = index;
        if (i >= progs.size()) return;
        Prog p = progs.get(i);

        float t = (SystemClock.uptimeMillis() - startMs) / 1000.0f;

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(p.program);
        GLES20.glUniform1f(p.uTime, t);

        // Built-ins are set first, so a plugin can add uniforms or override them.
        Gl.FrameCallback cb = frameCallback;
        if (cb != null) {
            drawing = p;
            cb.frame(t);
            drawing = null;
        }

        GLES20.glEnableVertexAttribArray(p.aPos);
        GLES20.glVertexAttribPointer(p.aPos, 2, GLES20.GL_FLOAT, false, 0, verts);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
        GLES20.glDisableVertexAttribArray(p.aPos);
    }

    /** Compile a pushed shader and swap it in. */
    PushServer.Result install(String name, String body) {
        if (!surfaceReady) return noSurface();

        Prog fresh = build(body);
        if (fresh == null) {
            return new PushServer.Result(false, lastError);
        }

        int at = names.indexOf(name);
        if (at < 0) {
            names.add(name);
            bodies.add(body);
            errors.add(null);
            progs.add(fresh);
            at = progs.size() - 1;
        } else {
            GLES20.glDeleteProgram(progs.get(at).program);
            progs.set(at, fresh);
            bodies.set(at, body);
            errors.set(at, null);
        }

        if (width > 0) applyResolution(at);
        index = at;
        return new PushServer.Result(true, "ok " + name + "\n");
    }

    /** Drop a preset that has no built-in to fall back to. GL thread only. */
    PushServer.Result remove(String name) {
        if (!surfaceReady) return noSurface();

        int at = names.indexOf(name);
        if (at < 0) return new PushServer.Result(false, "no preset called " + name + "\n");
        if (progs.size() <= 1) {
            return new PushServer.Result(false, "that's the last preset -- not removing it\n");
        }

        GLES20.glDeleteProgram(progs.get(at).program);
        progs.remove(at);
        names.remove(at);
        bodies.remove(at);
        errors.remove(at);
        if (index >= progs.size()) index = progs.size() - 1;
        return new PushServer.Result(true, "removed " + name + "\n");
    }

    private static PushServer.Result noSurface() {
        // 503 rather than 400: nothing is wrong with the shader, the app just has no drawing surface yet.
        return new PushServer.Result(false,
            "no GL surface yet -- open the app so it is actually drawing, then push again\n", 503);
    }

    /** Set by build() when it returns null, read straight after. GL thread only. */
    private String lastError = "";

    /** @return a linked program, or null with the driver's log in lastError. */
    private Prog build(String body) {
        int fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(fs, head + body);
        GLES20.glCompileShader(fs);

        int[] ok = new int[1];
        GLES20.glGetShaderiv(fs, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            lastError = GLES20.glGetShaderInfoLog(fs);
            GLES20.glDeleteShader(fs);
            return null;
        }

        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(fs);

        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            lastError = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            return null;
        }

        Prog p = new Prog();
        p.program = program;
        p.aPos  = GLES20.glGetAttribLocation(program, "a_pos");
        p.uRes  = GLES20.glGetUniformLocation(program, "u_res");
        p.uTime = GLES20.glGetUniformLocation(program, "u_time");
        return p;
    }

    /** Drivers sometimes fail with no log at all, which reads as success gone quiet. */
    private static String describe(String log, String stage) {
        if (log != null && !log.trim().isEmpty()) return log;
        return stage + " failed, and the driver returned no log\n";
    }

    private static int compileOrThrow(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new RuntimeException("shader compile failed: " + log);
        }
        return s;
    }
}
