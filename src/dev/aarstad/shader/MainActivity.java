package dev.aarstad.shader;

import android.app.Activity;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends Activity {

    private GLSurfaceView view;
    private PresetRenderer renderer;

    /** Mirrors the renderer's index so the toast can name the preset off the UI thread. */
    private int shown = 0;

    private int touchSlop;
    private float downX, downY;
    private long downAt;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        drawUnderCutout();

        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        renderer = new PresetRenderer();

        view = new GLSurfaceView(this);
        view.setEGLContextClientVersion(2);
        view.setRenderer(renderer);
        view.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        setContentView(view);

        goFullscreen();
    }

    /**
     * Immersive sticky: status and nav bars hidden, restored briefly by an edge
     * swipe and then hidden again on their own.
     *
     * setSystemUiVisibility is deprecated in favour of WindowInsetsController
     * (API 30), but we compile against android-23.jar -- the only platform
     * Debian's android-sdk ships -- so the controller isn't on the classpath.
     * The old flags still work because we target SDK 34; Android 15+ only
     * ignores them for apps targeting 35 or higher.
     */
    private void goFullscreen() {
        view.setSystemUiVisibility(
              View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /**
     * Let the shader run under the camera cutout. With the status bar hidden the
     * default policy letterboxes the window away from the cutout, which shows up
     * as a black band across the top.
     *
     * LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES and the field it goes in both
     * arrived in API 28, well after android-23, hence the reflection.
     */
    private void drawUnderCutout() {
        if (Build.VERSION.SDK_INT < 28) return;
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.getClass().getField("layoutInDisplayCutoutMode").setInt(lp, 1);
            getWindow().setAttributes(lp);
        } catch (Exception e) {
            // Vendor ROM without the field. The shader stops at the cutout
            // instead of running under it -- not worth crashing over.
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goFullscreen();
    }

    // GLSurfaceView isn't clickable, so touches fall through to the activity.
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downAt = e.getEventTime();
                return true;

            case MotionEvent.ACTION_UP:
                // Only a real tap cycles. In immersive mode the edge swipe that
                // reveals the system bars would otherwise count as one.
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                boolean moved = dx * dx + dy * dy > touchSlop * touchSlop;
                boolean held = e.getEventTime() - downAt > ViewConfiguration.getLongPressTimeout();
                if (!moved && !held) cycle();
                return true;
        }
        return false;
    }

    private void cycle() {
        shown = (shown + 1) % Presets.NAMES.length;
        final int next = shown;
        view.queueEvent(new Runnable() {
            @Override public void run() { renderer.select(next); }
        });
        Toast.makeText(this, Presets.NAMES[next], Toast.LENGTH_SHORT).show();
    }

    @Override protected void onPause() { super.onPause(); view.onPause(); }
    @Override protected void onResume() { super.onResume(); view.onResume(); }

    static class PresetRenderer implements GLSurfaceView.Renderer {

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

        private FloatBuffer verts;

        // One compiled program per preset. Every preset takes the same two
        // uniforms, so the draw path doesn't care which one is bound.
        private final int[] programs = new int[Presets.SOURCES.length];
        private final int[] aPos     = new int[Presets.SOURCES.length];
        private final int[] uRes     = new int[Presets.SOURCES.length];
        private final int[] uTime    = new int[Presets.SOURCES.length];

        /** Touched only on the GL thread, via GLSurfaceView.queueEvent. */
        private int index = 0;

        private int width, height;
        private long startMs;

        void select(int i) {
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

            int vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SRC);

            for (int i = 0; i < Presets.SOURCES.length; i++) {
                int fs = compile(GLES20.GL_FRAGMENT_SHADER, Presets.SOURCES[i]);

                int p = GLES20.glCreateProgram();
                GLES20.glAttachShader(p, vs);
                GLES20.glAttachShader(p, fs);
                GLES20.glLinkProgram(p);

                int[] ok = new int[1];
                GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
                if (ok[0] == 0) {
                    throw new RuntimeException(
                        "link failed for " + Presets.NAMES[i] + ": " + GLES20.glGetProgramInfoLog(p));
                }

                // The vertex shader is shared, so it stays alive until every
                // program that references it is linked; the fragment shader is
                // only needed by this one.
                GLES20.glDeleteShader(fs);

                programs[i] = p;
                aPos[i]  = GLES20.glGetAttribLocation(p, "a_pos");
                uRes[i]  = GLES20.glGetUniformLocation(p, "u_res");
                uTime[i] = GLES20.glGetUniformLocation(p, "u_time");
            }

            GLES20.glDeleteShader(vs);
            startMs = SystemClock.uptimeMillis();
        }

        @Override
        public void onSurfaceChanged(GL10 unused, int w, int h) {
            GLES20.glViewport(0, 0, w, h);
            width = w;
            height = h;
            for (int i = 0; i < programs.length; i++) applyResolution(i);
        }

        private void applyResolution(int i) {
            GLES20.glUseProgram(programs[i]);
            GLES20.glUniform2f(uRes[i], (float) width, (float) height);
        }

        @Override
        public void onDrawFrame(GL10 unused) {
            int i = index;
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(programs[i]);
            GLES20.glUniform1f(uTime[i], (SystemClock.uptimeMillis() - startMs) / 1000.0f);
            GLES20.glEnableVertexAttribArray(aPos[i]);
            GLES20.glVertexAttribPointer(aPos[i], 2, GLES20.GL_FLOAT, false, 0, verts);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
            GLES20.glDisableVertexAttribArray(aPos[i]);
        }

        private static int compile(int type, String src) {
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
}
