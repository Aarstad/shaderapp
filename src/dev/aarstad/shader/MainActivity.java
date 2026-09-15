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
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends Activity implements ShaderServer.Bridge {

    /** How long a plugin must keep the UI thread alive before the load counts as proven. */
    private static final long PROVEN_AFTER_MS = 2000;

    private GLSurfaceView view;

    /** Laid over the shader; the plugin owns everything in it. */
    private FrameLayout pluginContainer;

    private PresetRenderer renderer;
    private ShaderServer server;
    private PluginLoader plugins;
    private Gl gl;

    /** Scratch that outlives a plugin swap, since a push builds a new instance. */
    private final Bundle pluginState = new Bundle();

    private int port = -1;
    private String head;

    private int touchSlop;
    private float downX, downY;
    private long downAt;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        drawUnderCutout();

        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        head = Presets.head(this);
        renderer = new PresetRenderer(head, Presets.load(this));

        view = new GLSurfaceView(this);
        view.setEGLContextClientVersion(2);
        view.setRenderer(renderer);
        view.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        pluginContainer = new FrameLayout(this);

        // The shader draws underneath; anything a plugin adds sits on top.
        FrameLayout root = new FrameLayout(this);
        root.addView(view, matchParent());
        root.addView(pluginContainer, matchParent());
        setContentView(root);

        gl = new GlImpl();
        plugins = new PluginLoader(this, new HostImpl(), this::reclaim);

        Plugin restored = plugins.restore();
        if (restored != null && !attach(restored, "restored").ok) {
            // Most likely a plugin built against an older Plugin interface.
            // Whatever the reason, it will fail the same way next launch.
            plugins.forget();
        }

        goFullscreen();
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /**
     * Host-side teardown after any detach, from whichever thread noticed. The
     * container and the frame callback belong to the host, so it takes them
     * back rather than trusting a plugin that may have just thrown.
     */
    private void reclaim() {
        runOnUiThread(() -> pluginContainer.removeAllViews());
        renderer.setFrameCallback(null);
    }

    /** UI thread. Attaches and, if that works, starts the survival clock. */
    private ShaderServer.Result attach(Plugin fresh, String name) {
        ShaderServer.Result r = plugins.attach(fresh, name);
        if (r.ok) {
            view.postDelayed(plugins::proved, PROVEN_AFTER_MS);
        }
        return r;
    }

    /**
     * The push channel only exists while the app is on screen. Anything posted
     * to it has to reach the UI or GL thread, and a backgrounded activity
     * drains neither -- so rather than let pushes pile up invisibly, the socket
     * closes with the activity and the client gets a refused connection it can
     * report honestly.
     */
    @Override
    protected void onStart() {
        super.onStart();
        server = new ShaderServer(this, Presets.headLines(head), versionName());
        port = server.start();
        Toast.makeText(this,
            port > 0 ? "push: 127.0.0.1:" + port : "push channel unavailable (ports busy)",
            Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * Immersive sticky: status and nav bars hidden, restored briefly by an edge
     * swipe and then hidden again on their own.
     *
     * setSystemUiVisibility is deprecated in favour of WindowInsetsController
     * (API 30). The old flags still work because we target SDK 34; Android 15+
     * only ignores them for apps targeting 35 or higher, so raising targetSdk
     * and switching to the controller are one change, not two.
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
     * This used to go in by reflection: the field and the constant both arrived
     * in API 28 and the build compiled against android-23. Compiling against
     * android-34 makes it an ordinary assignment, still guarded because minSdk
     * is 21.
     */
    private void drawUnderCutout() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(lp);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goFullscreen();
    }

    // Touches reach the activity when nothing in the plugin's container claims
    // them first -- an ordinary view hierarchy, so a plugin that adds a button
    // gets normal button behaviour without going through this at all.
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int w = view.getWidth();
        int h = view.getHeight();
        if (w > 0 && h > 0) {
            float unit = Math.min(w, h);
            float sx = (e.getX() * 2f - w) / unit;
            // gl_FragCoord counts up from the bottom; MotionEvent counts down
            // from the top, so the y axis has to be flipped to match centred().
            float sy = ((h - e.getY()) * 2f - h) / unit;
            if (plugins.event(Plugin.TOUCH, e.getActionMasked(), sx, sy)) return true;
        }

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

    @Override
    public void onBackPressed() {
        if (plugins.event(Plugin.BACK)) return;
        super.onBackPressed();
    }

    private void cycle() {
        int count = renderer.names().size();
        if (count == 0) return;
        int next = (renderer.index() + 1) % count;
        show(next);
        Toast.makeText(this, renderer.names().get(next), Toast.LENGTH_SHORT).show();
    }

    private void show(int i) {
        view.queueEvent(() -> renderer.select(i));
    }

    @Override
    protected void onPause() {
        super.onPause();
        plugins.event(Plugin.PAUSE);
        view.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.onResume();
        plugins.event(Plugin.RESUME);
    }

    // ---- thread hops ---------------------------------------------------------
    //
    // Server-thread work that has to happen somewhere else, waiting for the
    // answer so a compile log or an attach failure can go back in the response.

    private interface Task {
        ShaderServer.Result run();
    }

    private ShaderServer.Result onGl(Task task) {
        return hop(task, r -> view.queueEvent(r),
            "the app isn't rendering -- bring it to the foreground and push again\n");
    }

    private ShaderServer.Result onUi(Task task) {
        return hop(task, this::runOnUiThread,
            "the UI thread isn't responding -- is the app on screen?\n");
    }

    private ShaderServer.Result hop(Task task, java.util.function.Consumer<Runnable> to,
                                    String timeoutMessage) {
        AtomicReference<ShaderServer.Result> slot = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        to.accept(() -> {
            try {
                slot.set(task.run());
            } catch (Throwable t) {
                slot.set(new ShaderServer.Result(false, "internal error: " + t + "\n"));
            } finally {
                done.countDown();
            }
        });

        try {
            if (!done.await(5, TimeUnit.SECONDS)) {
                return new ShaderServer.Result(false, timeoutMessage);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ShaderServer.Result(false, "interrupted\n");
        }
        return slot.get();
    }

    // ---- ShaderServer.Bridge -------------------------------------------------

    @Override
    public ShaderServer.Result install(String name, String body) {
        ShaderServer.Result r = onGl(() -> renderer.install(name, body));
        if (!r.ok) return r;

        // Persist only what actually compiled, so a bad push can never be
        // reloaded into a broken app at next launch.
        try {
            Presets.save(this, name, body);
        } catch (IOException e) {
            return new ShaderServer.Result(true,
                r.log + "warning: live but not persisted (" + e.getMessage() + ")\n");
        }
        toast("↻ " + name);
        return r;
    }

    @Override
    public ShaderServer.Result revert(String name) {
        if (!Presets.validName(name)) {
            return new ShaderServer.Result(false, "bad preset name\n");
        }
        if (!Presets.revert(this, name)) {
            return new ShaderServer.Result(false, "nothing pushed under the name " + name + "\n");
        }

        String builtIn = Presets.builtInBody(this, name);
        if (builtIn != null) {
            ShaderServer.Result r = onGl(() -> renderer.install(name, builtIn));
            if (r.ok) {
                toast("↺ " + name);
                return new ShaderServer.Result(true, "reverted " + name + " to the built-in\n");
            }
            return r;
        }

        ShaderServer.Result r = onGl(() -> renderer.remove(name));
        if (r.ok) toast("− " + name);
        return r;
    }

    @Override
    public ShaderServer.Result select(String which) {
        List<String> names = renderer.names();
        int target = names.indexOf(which);
        if (target < 0) {
            try {
                int i = Integer.parseInt(which.trim());
                if (i >= 0 && i < names.size()) target = i;
            } catch (NumberFormatException ignored) {
                // Not an index either -- falls through to the 404 below.
            }
        }
        if (target < 0) {
            return new ShaderServer.Result(false, "no preset called " + which + "\n");
        }
        show(target);
        String shownName = names.get(target);
        toast(shownName);
        return new ShaderServer.Result(true, shownName + "\n");
    }

    @Override
    public String describe() {
        List<String> names = renderer.names();
        int at = renderer.index();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            sb.append(i == at ? "* " : "  ")
              .append(i).append(' ').append(names.get(i));
            String err = renderer.errorFor(i);
            if (err != null) sb.append("   [fallback: ").append(err).append(']');
            sb.append('\n');
        }
        return sb.toString();
    }

    @Override
    public ShaderServer.Result installPlugin(byte[] dex, String className) {
        String name = className == null || className.trim().isEmpty()
            ? PluginLoader.DEFAULT_CLASS : className.trim();

        Plugin fresh;
        try {
            // Writing the dex and loading its classes is plain file and
            // classloader work, so it stays off the UI thread; only attach()
            // has to happen there, since a plugin builds views in it.
            fresh = plugins.load(dex, name);
        } catch (Throwable t) {
            return new ShaderServer.Result(false, "load failed: " + t + "\n");
        }

        ShaderServer.Result r = onUi(() -> attach(fresh, plugins.labelFor(name)));
        if (!r.ok) plugins.forget();
        else toast("↻ " + plugins.labelFor(name));
        return r;
    }

    @Override
    public ShaderServer.Result dropPlugin() {
        if (!plugins.active()) {
            plugins.forget();
            return new ShaderServer.Result(false, "no plugin loaded\n");
        }
        onUi(() -> {
            plugins.drop();
            return new ShaderServer.Result(true, "");
        });
        plugins.forget();
        toast("− plugin");
        return new ShaderServer.Result(true, "plugin detached\n");
    }

    @Override
    public String pluginStatus() {
        String logs = plugins.logs();
        return plugins.status() + (logs.isEmpty() ? "" : "\n" + logs.trim());
    }

    @Override
    public ShaderServer.Result command(String line) {
        String reply = plugins.command(line);
        if (reply == null) {
            return new ShaderServer.Result(false, "no plugin loaded\n");
        }
        return new ShaderServer.Result(true, reply.endsWith("\n") ? reply : reply + "\n");
    }

    // ---- what plugin code is handed ------------------------------------------

    private final class HostImpl implements Plugin.Host {
        @Override public Activity activity() { return MainActivity.this; }
        @Override public ViewGroup container() { return pluginContainer; }

        @Override public File dataDir() {
            File d = new File(getFilesDir(), "plugin-data");
            if (!d.isDirectory()) d.mkdirs();
            return d;
        }

        @Override public Bundle state() { return pluginState; }
        @Override public void log(String message) { plugins.note(message); }
        @Override public void toast(String message) { MainActivity.this.toast(message); }
        @Override public void post(Runnable action) { runOnUiThread(action); }

        @Override public Object extension(String name) {
            return "gl".equals(name) ? gl : null;
        }
    }

    /** The "gl" extension: the shader half of the app, offered to plugins that want it. */
    private final class GlImpl implements Gl {
        @Override public void onFrame(FrameCallback callback) {
            renderer.setFrameCallback(plugins.guard(callback));
        }
        @Override public void setUniform(String name, float... values) {
            renderer.setUniform(name, values);
        }
        @Override public void setFloat(String name, float value) {
            renderer.setFloat(name, value);
        }
        @Override public int presetCount() { return renderer.names().size(); }
        @Override public String presetName(int i) {
            List<String> n = renderer.names();
            return i >= 0 && i < n.size() ? n.get(i) : null;
        }
        @Override public int currentPreset() { return renderer.index(); }
        @Override public void select(int i) { show(i); }
        @Override public float seconds() { return renderer.seconds(); }
    }

    private void toast(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
    }

    // ---- renderer ------------------------------------------------------------

    static final class PresetRenderer implements GLSurfaceView.Renderer {

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
         * The shared vertex shader, kept alive for the life of the context
         * rather than deleted after setup, because a pushed preset needs
         * something to link against later.
         */
        private int vs;

        private volatile int index = 0;
        private volatile Gl.FrameCallback frameCallback;

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

        List<String> names() { return names; }
        int index() { return index; }
        float seconds() { return (SystemClock.uptimeMillis() - startMs) / 1000.0f; }
        String errorFor(int i) { return i < errors.size() ? errors.get(i) : null; }

        /**
         * Write a uniform on the program that is drawing this frame. A name the
         * shader doesn't declare resolves to -1 and is dropped, which is what
         * lets a single plugin feed a whole cycle of unrelated shaders.
         *
         * Only meaningful from inside a frame callback; anywhere else there is
         * no bound program and this is a no-op rather than a crash.
         */
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
                    // A persisted shader that no longer compiles -- on this
                    // driver, or after an edit saved before a context loss.
                    // Draw the fallback rather than crash on launch.
                    errors.set(i, lastError);
                    p = build(Presets.FALLBACK_BODY);
                }
                progs.add(p);
            }
            if (index >= progs.size()) index = 0;
            startMs = SystemClock.uptimeMillis();
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

            // Built-ins are set first, so a plugin can add uniforms or override
            // them. drawing is what setUniform() resolves names against.
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

        /**
         * Compile a pushed shader and swap it in. GL thread only.
         *
         * The new program is built to completion before anything is replaced,
         * so a shader that fails to compile leaves the running one untouched --
         * you keep looking at the last good version while you fix it.
         */
        ShaderServer.Result install(String name, String body) {
            Prog fresh = build(body);
            if (fresh == null) {
                return new ShaderServer.Result(false, lastError);
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
            return new ShaderServer.Result(true, "ok " + name + "\n");
        }

        /** Drop a preset that has no built-in to fall back to. GL thread only. */
        ShaderServer.Result remove(String name) {
            int at = names.indexOf(name);
            if (at < 0) return new ShaderServer.Result(false, "no preset called " + name + "\n");
            if (progs.size() <= 1) {
                return new ShaderServer.Result(false, "that's the last preset -- not removing it\n");
            }

            GLES20.glDeleteProgram(progs.get(at).program);
            progs.remove(at);
            names.remove(at);
            bodies.remove(at);
            errors.remove(at);
            if (index >= progs.size()) index = progs.size() - 1;
            return new ShaderServer.Result(true, "removed " + name + "\n");
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
}
