package dev.aarstad.shader;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

import dalvik.system.DexClassLoader;

/**
 * Loads pushed dex, holds the live plugin, and makes sure a bad one can't take
 * the app with it.
 *
 * Every call into plugin code goes through here wrapped in a catch of
 * Throwable. A plugin that throws is logged, detached and forgotten, and the
 * app falls back to its built-in behaviour mid-frame.
 *
 * That covers a plugin that throws. It does not cover one that deadlocks the
 * UI thread or kills the process outright -- and since plugins reload at
 * launch, that is a crash loop. So loading is armed: a marker file is written
 * before a plugin first runs, and cleared only once the host has seen the UI
 * thread still running a couple of seconds after attach() returned. Finding
 * that marker at startup means the last attempt never got that far, so the
 * plugin is left disabled rather than loaded again. push.sh reports it, and
 * pushing again re-arms.
 *
 * Note that swapping a plugin leaks its predecessor's classes: the old
 * DexClassLoader is dropped, but loaded classes are only collected once
 * nothing references them, and the runtime is conservative about that. It is a
 * few KB per swap on a development channel, which is a fair trade for not
 * having to restart.
 */
final class PluginLoader {

    /** Class every pushed dex must provide, unless a push names another. */
    static final String DEFAULT_CLASS = "dev.aarstad.shader.plugin.Main";

    private static final int LOG_LINES = 40;

    private final Context ctx;
    private final Plugin.Host host;

    /** Host-side teardown after any detach: clear the container, drop callbacks. */
    private final Runnable cleanup;

    /** Read on the GL and server threads; written when a plugin comes or goes. */
    private volatile Plugin plugin;
    private volatile String label = "none";

    /** Cleared once the current plugin has survived a frame. */
    private volatile boolean armed;

    private final Deque<String> log = new ArrayDeque<String>();

    PluginLoader(Context ctx, Plugin.Host host, Runnable cleanup) {
        this.ctx = ctx;
        this.host = host;
        this.cleanup = cleanup;
    }

    // ---- state ---------------------------------------------------------------

    boolean active() { return plugin != null; }

    String status() {
        Plugin p = plugin;
        if (p != null) return "plugin " + label + (armed ? " (unproven)" : " (ok)");
        return disarmFile().isFile() ? "plugin disabled after a failed load" : "plugin none";
    }

    /**
     * The host reporting that the UI thread is still alive well after attach.
     * Anything that was going to deadlock or kill the process has had its
     * chance, so stop holding the load against the plugin at next launch.
     */
    void proved() {
        if (!armed || plugin == null) return;
        armed = false;
        disarmFile().delete();
    }

    void note(String line) {
        synchronized (log) {
            log.addLast(line);
            while (log.size() > LOG_LINES) log.removeFirst();
        }
    }

    String logs() {
        StringBuilder sb = new StringBuilder();
        synchronized (log) {
            for (String s : log) sb.append(s).append('\n');
        }
        return sb.toString();
    }

    // ---- dispatch ------------------------------------------------------------
    //
    // Each of these is a no-op when no plugin is loaded, so callers don't branch.

    boolean event(String name, Object... args) {
        Plugin p = plugin;
        if (p == null) return false;
        try {
            return p.event(name, args);
        } catch (Throwable t) {
            fail("event " + name, t);
            return false;
        }
    }

    /**
     * Wrap a plugin-supplied callback in the same guard everything else gets,
     * so a throw from inside a per-frame callback detaches rather than killing
     * the render thread.
     */
    Gl.FrameCallback guard(final Gl.FrameCallback inner) {
        if (inner == null) return null;
        return seconds -> {
            if (plugin == null) return;
            try {
                inner.frame(seconds);
            } catch (Throwable t) {
                fail("frame", t);
            }
        };
    }

    String command(String line) {
        Plugin p = plugin;
        if (p == null) return null;
        try {
            String reply = p.command(line);
            return reply == null ? "" : reply;
        } catch (Throwable t) {
            fail("command", t);
            return "plugin failed: " + t;
        }
    }

    /** UI thread. Attaches a freshly loaded plugin, replacing any predecessor. */
    ShaderServer.Result attach(Plugin fresh, String name) {
        drop();
        try {
            fresh.attach(host);
        } catch (Throwable t) {
            note("attach failed: " + t);
            return new ShaderServer.Result(false, "attach failed: " + t + "\n");
        }
        plugin = fresh;
        label = name;
        note("attached " + name);
        return new ShaderServer.Result(true, "attached " + name + "\n");
    }

    /** UI thread. Detaches the current plugin, if any. */
    void drop() {
        Plugin p = plugin;
        plugin = null;
        if (p == null) {
            return;
        }
        try {
            p.detach();
        } catch (Throwable t) {
            note("detach threw: " + t);
        } finally {
            // Runs even if the plugin's own cleanup threw -- the container and
            // the frame callback are the host's to reclaim either way.
            cleanup.run();
        }
    }

    private void fail(String where, Throwable t) {
        note("plugin threw in " + where + "(): " + t);
        drop();
        // Don't reload it next launch -- whatever is wrong will still be wrong.
        pointerFile().delete();
        host.toast("plugin detached: " + t);
    }

    // ---- loading -------------------------------------------------------------

    private File dir() {
        File d = new File(ctx.getFilesDir(), "plugin");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private File pointerFile() { return new File(dir(), "current"); }
    private File disarmFile()  { return new File(dir(), "armed"); }

    /**
     * Writes the dex and loads its entry class. Off the GL thread -- the caller
     * attaches the result there.
     *
     * @throws Exception with a message worth showing the user
     */
    Plugin load(byte[] dex, String className) throws Exception {
        if (dex.length == 0) throw new IOException("empty dex");

        // A fresh filename every time. DexClassLoader caches its optimised
        // output against the path, and reusing one invites a stale hit.
        File file = new File(dir(), "plugin-" + System.currentTimeMillis() + ".dex");
        FileOutputStream os = new FileOutputStream(file);
        try {
            os.write(dex);
        } finally {
            os.close();
        }

        // Android 14 refuses to load a dex file that is still writable.
        if (!file.setReadOnly()) {
            file.delete();
            throw new IOException("could not make the dex read-only");
        }

        Plugin p = instantiate(file, className);

        // Only now is it worth keeping: record it, arm the guard, bin the rest.
        write(pointerFile(), file.getName() + "\n" + className);
        write(disarmFile(), "1");
        armed = true;
        sweep(file);
        return p;
    }

    private Plugin instantiate(File dex, String className) throws Exception {
        File odex = new File(dir(), "oat");
        if (!odex.isDirectory()) odex.mkdirs();

        // Parent is the app's own loader, so Plugin and Plugin.Host resolve to
        // the very same classes the app is holding -- which is the whole reason
        // the cast below is legal across a classloader boundary.
        DexClassLoader loader = new DexClassLoader(
            dex.getAbsolutePath(), odex.getAbsolutePath(), null,
            PluginLoader.class.getClassLoader());

        Class<?> type;
        try {
            type = loader.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new ClassNotFoundException("no class " + className + " in the pushed dex");
        }
        if (!Plugin.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(className + " does not implement Plugin");
        }
        return (Plugin) type.newInstance();
    }

    /** Reload the last good plugin at startup, unless the guard says not to. */
    Plugin restore() {
        File pointer = pointerFile();
        if (!pointer.isFile()) return null;

        if (disarmFile().isFile()) {
            note("last plugin never survived a frame -- left disabled");
            pointer.delete();
            return null;
        }

        try {
            String[] lines = read(pointer).split("\n");
            File dex = new File(dir(), lines[0].trim());
            if (!dex.isFile()) return null;
            String className = lines.length > 1 ? lines[1].trim() : DEFAULT_CLASS;

            Plugin p = instantiate(dex, className);
            write(disarmFile(), "1");
            armed = true;
            return p;
        } catch (Throwable t) {
            note("could not restore plugin: " + t);
            pointer.delete();
            return null;
        }
    }

    /** Forget the plugin entirely, so nothing is reloaded at next launch. */
    void forget() {
        pointerFile().delete();
        disarmFile().delete();
        armed = false;
    }

    String labelFor(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    /** Delete every dex except the one in use. */
    private void sweep(File keep) {
        File[] all = dir().listFiles();
        if (all == null) return;
        for (File f : all) {
            if (f.getName().endsWith(".dex") && !f.equals(keep)) f.delete();
        }
    }

    private static void write(File f, String s) throws IOException {
        FileOutputStream os = new FileOutputStream(f);
        try {
            os.write(s.getBytes("UTF-8"));
        } finally {
            os.close();
        }
    }

    private static String read(File f) throws IOException {
        byte[] buf = new byte[(int) f.length()];
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            int n = in.read(buf);
            return new String(buf, 0, Math.max(n, 0), "UTF-8");
        } finally {
            in.close();
        }
    }
}
