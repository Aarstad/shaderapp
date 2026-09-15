package dev.aarstad.shader.gl;

import android.content.SharedPreferences;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Toast;

import java.io.IOException;
import java.util.List;

import dev.aarstad.shader.host.HostActivity;
import dev.aarstad.shader.host.PushServer;

/**
 * The shader module: a background that draws GLSL, a "gl" capability offered
 * to plugins, and the preset routes on the push channel.
 */
public final class ShaderActivity extends HostActivity {

    private static final String PREFS = "shader";
    private static final String KEY_PRESET = "preset";

    private GLSurfaceView view;
    private PresetRenderer renderer;
    private String head;

    private int touchSlop;
    private float downX, downY;
    private long downAt;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
    }

    @Override
    protected View onCreateBackground() {
        head = Presets.head(this);
        renderer = new PresetRenderer(head, Presets.load(this));

        // Reopen on whatever was last being watched.
        String last = prefs().getString(KEY_PRESET, null);
        if (last != null) renderer.setInitialIndex(renderer.names().indexOf(last));

        view = new GLSurfaceView(this);
        view.setEGLContextClientVersion(2);
        // Without this the EGL context is destroyed whenever the app leaves the
        // foreground, while queued events keep running on the GL thread -- so a
        // pushed shader compiles against nothing and fails with an empty log.
        view.setPreserveEGLContextOnPause(true);
        view.setRenderer(renderer);
        view.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        return view;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    @Override
    protected void reclaim() {
        super.reclaim();
        // The frame callback is this module's to take back, not the host's.
        if (renderer != null) renderer.setFrameCallback(null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        view.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.onResume();
    }

    /** A touch the plugin didn't claim. Only a real tap cycles. */
    @Override
    protected boolean onBackgroundTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downAt = e.getEventTime();
                return true;

            case MotionEvent.ACTION_UP:
                // In immersive mode the edge swipe that reveals the system bars would otherwise count as a tap.
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
        int count = renderer.names().size();
        if (count == 0) return;
        int next = (renderer.index() + 1) % count;
        show(next);
        Toast.makeText(this, renderer.names().get(next), Toast.LENGTH_SHORT).show();
    }

    private void show(int i) {
        view.queueEvent(() -> renderer.select(i));
        remember(i);
    }

    private void remember(int i) {
        List<String> names = renderer.names();
        if (i >= 0 && i < names.size()) {
            prefs().edit().putString(KEY_PRESET, names.get(i)).apply();
        }
    }

    private PushServer.Result onGl(Task task) {
        return hop(task, r -> view.queueEvent(r),
            "the app isn't rendering -- bring it to the foreground and push again\n");
    }

    // ---- what the host asks of a module --------------------------------------

    @Override
    public String info() {
        return "headLines " + Presets.headLines(head) + "\n" + describe();
    }

    @Override
    protected Object extension(String name) {
        return "gl".equals(name) ? glApi : null;
    }

    @Override
    public PushServer.Extra extra() {
        return routes;
    }

    private final Gl glApi = new Gl() {
        @Override public void onFrame(FrameCallback callback) {
            if (callback == null) {
                renderer.setFrameCallback(null);
                return;
            }
            // Plugin code, so it runs under the host's catch-and-detach rule.
            renderer.setFrameCallback(seconds -> {
                if (!plugins.active()) return;
                try {
                    callback.frame(seconds);
                } catch (Throwable t) {
                    plugins.failed("frame", t);
                }
            });
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
    };

    private final PushServer.Extra routes = new PushServer.Extra() {
        @Override
        public PushServer.Result handle(String method, String path, byte[] raw) {
            if (path.equals("/presets") && method.equals("GET")) {
                return new PushServer.Result(true, describe());
            }
            if (path.startsWith("/preset/")) {
                String name = path.substring("/preset/".length());
                if (!Presets.validName(name)) {
                    return new PushServer.Result(false,
                        "bad preset name: letters, digits, _ and - only\n");
                }
                if (method.equals("POST") || method.equals("PUT")) {
                    String body = raw.length == 0 ? "" : new String(raw, java.nio.charset.StandardCharsets.UTF_8);
                    if (body.trim().isEmpty()) {
                        return new PushServer.Result(false, "empty body -- nothing to compile\n");
                    }
                    return install(name, body);
                }
                if (method.equals("DELETE")) {
                    return revert(name);
                }
            }
            if (path.startsWith("/select/") && method.equals("POST")) {
                return select(path.substring("/select/".length()));
            }
            return null;
        }

        @Override
        public String help() {
            return "GET    /presets\n"
                 + "POST   /preset/<Name>   body: fragment shader source\n"
                 + "DELETE /preset/<Name>   revert to the built-in\n"
                 + "POST   /select/<Name>   switch the displayed preset\n";
        }
    };

    // ---- preset routes -------------------------------------------------------

    private PushServer.Result install(String name, String body) {
        PushServer.Result r = onGl(() -> renderer.install(name, body));
        if (!r.ok) return r;

        // Persist only what actually compiled, so a bad push can never be reloaded into a broken app at next launch.
        try {
            Presets.save(this, name, body);
        } catch (IOException e) {
            return new PushServer.Result(true,
                r.log + "warning: live but not persisted (" + e.getMessage() + ")\n");
        }
        remember(renderer.index());
        toast("↻ " + name);
        return r;
    }

    private PushServer.Result revert(String name) {
        if (!Presets.revert(this, name)) {
            return new PushServer.Result(false,
                "nothing pushed under the name " + name + "\n", 404);
        }

        String builtIn = Presets.builtInBody(this, name);
        if (builtIn != null) {
            PushServer.Result r = onGl(() -> renderer.install(name, builtIn));
            if (r.ok) {
                toast("↺ " + name);
                return new PushServer.Result(true, "reverted " + name + " to the built-in\n");
            }
            return r;
        }

        PushServer.Result r = onGl(() -> renderer.remove(name));
        if (r.ok) toast("− " + name);
        return r;
    }

    private PushServer.Result select(String which) {
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
            return new PushServer.Result(false, "no preset called " + which + "\n", 404);
        }
        show(target);
        String shownName = names.get(target);
        toast(shownName);
        return new PushServer.Result(true, shownName + "\n");
    }

    private String describe() {
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
}
