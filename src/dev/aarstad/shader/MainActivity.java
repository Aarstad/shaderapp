package dev.aarstad.shader;

import android.app.Activity;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends Activity {

    private GLSurfaceView view;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        view = new GLSurfaceView(this);
        view.setEGLContextClientVersion(2);
        view.setRenderer(new PlasmaRenderer());
        view.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        setContentView(view);
    }

    @Override protected void onPause() { super.onPause(); view.onPause(); }
    @Override protected void onResume() { super.onResume(); view.onResume(); }

    static class PlasmaRenderer implements GLSurfaceView.Renderer {

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

        // Domain-warped plasma. Five warp iterations is about the most a
        // mid-range Mali will hold at 60fps on a 1080p-class screen.
        private static final String FRAGMENT_SRC =
            "precision highp float;\n" +
            "uniform vec2 u_res;\n" +
            "uniform float u_time;\n" +
            "void main() {\n" +
            "    vec2 uv = (gl_FragCoord.xy * 2.0 - u_res) / min(u_res.x, u_res.y);\n" +
            "    float t = u_time * 0.35;\n" +
            "    vec2 p = uv;\n" +
            "    for (int i = 0; i < 5; i++) {\n" +
            "        p += vec2(sin(p.y * 3.0 + t), cos(p.x * 3.0 - t)) * 0.25;\n" +
            "    }\n" +
            "    float d = length(p);\n" +
            "    vec3 col = 0.5 + 0.5 * cos(vec3(0.0, 2.1, 4.2) + d * 3.0 + t);\n" +
            "    col *= 1.0 - 0.35 * length(uv);\n" +
            "    gl_FragColor = vec4(col, 1.0);\n" +
            "}\n";

        private FloatBuffer verts;
        private int program;
        private int aPos, uRes, uTime;
        private long startMs;

        @Override
        public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            ByteBuffer bb = ByteBuffer.allocateDirect(VERTS.length * 4);
            bb.order(ByteOrder.nativeOrder());
            verts = bb.asFloatBuffer();
            verts.put(VERTS).position(0);

            int vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SRC);
            int fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SRC);

            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vs);
            GLES20.glAttachShader(program, fs);
            GLES20.glLinkProgram(program);

            int[] ok = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) {
                throw new RuntimeException("link failed: " + GLES20.glGetProgramInfoLog(program));
            }

            aPos  = GLES20.glGetAttribLocation(program, "a_pos");
            uRes  = GLES20.glGetUniformLocation(program, "u_res");
            uTime = GLES20.glGetUniformLocation(program, "u_time");
            startMs = SystemClock.uptimeMillis();
        }

        @Override
        public void onSurfaceChanged(GL10 unused, int w, int h) {
            GLES20.glViewport(0, 0, w, h);
            GLES20.glUseProgram(program);
            GLES20.glUniform2f(uRes, (float) w, (float) h);
        }

        @Override
        public void onDrawFrame(GL10 unused) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            GLES20.glUniform1f(uTime, (SystemClock.uptimeMillis() - startMs) / 1000.0f);
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, verts);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
            GLES20.glDisableVertexAttribArray(aPos);
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
