package dev.aarstad.shader.host;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Everything an app needs to host pushed code, and nothing about what it draws.
 *
 * Subclass it, optionally return a background view from
 * {@link #onCreateBackground}, and optionally offer capabilities through
 * {@link #extension} and routes through {@link #extra}. The shader module does
 * exactly that; delete it and this still runs, showing whatever the plugin
 * builds in its container.
 */
public class HostActivity extends Activity implements PushServer.Bridge {

    /** How long a plugin must keep the UI thread alive before the load counts as proven. */
    private static final long PROVEN_AFTER_MS = 2000;

    /**
     * The running host, so a second activity or a service can reach the same
     * plugin. Single-activity app, so a plain static is honest; it is cleared
     * in onDestroy rather than leaked.
     */
    private static volatile HostActivity current;

    public static HostActivity current() { return current; }

    /** Laid over the background; the plugin owns everything in it. */
    private FrameLayout pluginContainer;

    private View background;
    protected PluginLoader plugins;
    private PushServer server;
    private int port = -1;

    /** Scratch that outlives a plugin swap, since a push builds a new instance. */
    private final Bundle pluginState = new Bundle();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        current = this;

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        drawUnderCutout();

        FrameLayout root = new FrameLayout(this);
        background = onCreateBackground();
        if (background != null) root.addView(background, matchParent());

        pluginContainer = new FrameLayout(this);
        root.addView(pluginContainer, matchParent());
        setContentView(root);

        plugins = new PluginLoader(this, new HostImpl(), this::reclaim);

        Plugin restored = plugins.restore();
        if (restored != null && !attach(restored, "restored").ok) {
            // Most likely a plugin built against an older Plugin interface.
            // Whatever the reason, it will fail the same way next launch.
            plugins.forget();
        }

        startServer();
        goFullscreen();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (server != null) {
            server.stop();
            server = null;
        }
        if (current == this) current = null;
    }

    /** The view the plugin's container sits on top of, or null for nothing. */
    protected View onCreateBackground() {
        return null;
    }

    protected static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /**
     * Host-side teardown after any detach, from whichever thread noticed. The
     * container belongs to the host, so it takes it back rather than trusting a
     * plugin that may have just thrown.
     */
    protected void reclaim() {
        runOnUiThread(() -> pluginContainer.removeAllViews());
    }

    /** UI thread. Attaches and, if that works, starts the survival clock. */
    private PushServer.Result attach(Plugin fresh, String name) {
        PushServer.Result r = plugins.attach(fresh, name);
        if (r.ok) {
            pluginContainer.postDelayed(plugins::proved, PROVEN_AFTER_MS);
        }
        return r;
    }

    /**
     * The push channel lives as long as the activity, not as long as it is on
     * screen.
     *
     * It used to close in onStop, on the reasoning that a backgrounded activity
     * cannot drain the UI thread. That was simply wrong: the main looper keeps
     * running, so runOnUiThread works perfectly well in the background. Only
     * the GL thread stops, because GLSurfaceView.onPause halts it -- so shader
     * compiles genuinely need the foreground, and everything else does not.
     *
     * The old behaviour also broke the second screen, which backgrounds this
     * activity and so cut the channel exactly when a plugin most wanted it.
     *
     * The cost is that the socket is open whenever the app is alive rather than
     * only while you are looking at it. On loopback, on a phone you own, that
     * is the better trade.
     */
    private void startServer() {
        server = new PushServer(this, versionName());
        port = server.start();
        Toast.makeText(this,
            port > 0 ? "push: 127.0.0.1:" + port : "push channel unavailable (ports busy)",
            Toast.LENGTH_SHORT).show();
    }

    protected String versionName() {
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
    protected void goFullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(
              View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /**
     * Draw under the camera cutout. With the status bar hidden the default
     * policy letterboxes the window away from it, which shows up as a black
     * band across the top.
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
        int w = pluginContainer.getWidth();
        int h = pluginContainer.getHeight();
        if (w > 0 && h > 0) {
            float unit = Math.min(w, h);
            float sx = (e.getX() * 2f - w) / unit;
            // Flipped so the y axis matches GL's, which counts up from the
            // bottom, rather than MotionEvent's, which counts down from the top.
            float sy = ((h - e.getY()) * 2f - h) / unit;
            if (plugins.event(Plugin.TOUCH, e.getActionMasked(), sx, sy)) return true;
        }
        return onBackgroundTouch(e);
    }

    /** Touches the plugin did not claim. Default does nothing. */
    protected boolean onBackgroundTouch(MotionEvent e) {
        return false;
    }

    @Override
    public void onBackPressed() {
        if (plugins.event(Plugin.BACK)) return;
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        plugins.event(Plugin.PAUSE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        plugins.event(Plugin.RESUME);
    }

    // ---- thread hops ---------------------------------------------------------

    /** Server-thread work that has to happen somewhere else, with the answer returned. */
    public interface Task {
        PushServer.Result run();
    }

    protected PushServer.Result onUi(Task task) {
        return hop(task, this::runOnUiThread,
            "the UI thread isn't responding -- is the app on screen?\n");
    }

    protected PushServer.Result hop(Task task, Consumer<Runnable> to, String timeoutMessage) {
        AtomicReference<PushServer.Result> slot = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        to.accept(() -> {
            try {
                slot.set(task.run());
            } catch (Throwable t) {
                slot.set(new PushServer.Result(false, "internal error: " + t + "\n"));
            } finally {
                done.countDown();
            }
        });

        try {
            if (!done.await(5, TimeUnit.SECONDS)) {
                return new PushServer.Result(false, timeoutMessage, 503);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new PushServer.Result(false, "interrupted\n");
        }
        return slot.get();
    }

    // ---- PushServer.Bridge ---------------------------------------------------

    @Override
    public PushServer.Result installPlugin(byte[] dex, String className) {
        String name = className == null || className.trim().isEmpty()
            ? PluginLoader.DEFAULT_CLASS : className.trim();

        Plugin fresh;
        try {
            // Writing the dex and loading its classes is plain file and
            // classloader work, so it stays off the UI thread; only attach()
            // has to happen there, since a plugin builds views in it.
            fresh = plugins.load(dex, name);
        } catch (Throwable t) {
            return new PushServer.Result(false, "load failed: " + t + "\n");
        }

        PushServer.Result r = onUi(() -> attach(fresh, plugins.labelFor(name)));
        if (!r.ok) plugins.forget();
        else toast("↻ " + plugins.labelFor(name));
        return r;
    }

    @Override
    public PushServer.Result dropPlugin() {
        if (!plugins.active()) {
            plugins.forget();
            return new PushServer.Result(false, "no plugin loaded\n", 404);
        }
        onUi(() -> {
            plugins.drop();
            return new PushServer.Result(true, "");
        });
        plugins.forget();
        toast("− plugin");
        return new PushServer.Result(true, "plugin detached\n");
    }

    @Override
    public String pluginStatus() {
        String logs = plugins.logs();
        return plugins.status() + (logs.isEmpty() ? "" : "\n" + logs.trim());
    }

    @Override
    public PushServer.Result command(String line) {
        String reply = plugins.command(line);
        if (reply == null) {
            return new PushServer.Result(false, "no plugin loaded\n", 404);
        }
        return new PushServer.Result(true, reply.endsWith("\n") ? reply : reply + "\n");
    }

    @Override
    public File dataDir() {
        File d = new File(getFilesDir(), "plugin-data");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** Extra lines for /health. */
    @Override
    public String info() {
        return "";
    }

    /** App-specific routes. */
    @Override
    public PushServer.Extra extra() {
        return null;
    }

    /** Optional capabilities offered to plugins by name. */
    protected Object extension(String name) {
        return null;
    }

    protected void toast(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
    }

    // ---- what plugin code is handed ------------------------------------------

    private final class HostImpl implements Plugin.Host {
        @Override public Activity activity() { return HostActivity.this; }
        @Override public ViewGroup container() { return pluginContainer; }
        @Override public File dataDir() { return HostActivity.this.dataDir(); }
        @Override public Bundle state() { return pluginState; }
        @Override public void log(String message) { plugins.note(message); }
        @Override public void toast(String message) { HostActivity.this.toast(message); }
        @Override public void post(Runnable action) { runOnUiThread(action); }
        @Override public Object extension(String name) { return HostActivity.this.extension(name); }
    }
}
