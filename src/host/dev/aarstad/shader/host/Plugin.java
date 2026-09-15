package dev.aarstad.shader.host;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;

import java.io.File;

/**
 * The contract between the app and code pushed into it at runtime.
 *
 * A class already loaded can never be replaced -- only new classes from a new
 * loader. So the app splits: what must stay put is in the APK, and what is
 * worth iterating on lives behind this interface, in a dex that gets pushed.
 * This interface is therefore the one thing a push cannot change. Adding a
 * method here means a reinstall.
 *
 * Which shapes the whole design. There are four methods, and only the two that
 * are certain to be needed forever -- coming and going -- are typed. Everything
 * else arrives through {@link #event}, a deliberately loose channel keyed by
 * name, and every host capability through {@link Host#extension}, likewise. A
 * host that grows a new capability still needs rebuilding, but the *interface*
 * does not change, so plugins written against the old one keep loading and can
 * feature-detect what they are running on.
 *
 * That is a real trade: stringly-typed dispatch instead of compiler-checked
 * signatures. It buys never having to reinstall to teach the app a new trick,
 * which on a device where every install costs a tap is worth more than the
 * type checking. In Kotlin a `when (name)` makes the plugin side read cleanly
 * anyway.
 *
 * A plugin is a class dev.aarstad.shader.plugin.Main with a no-argument
 * constructor. Anything it throws is caught: the plugin is detached and the app
 * carries on, so a bad push degrades rather than crashes.
 */
public interface Plugin {

    // ---- event names ---------------------------------------------------------
    // Passed to event(). Unknown names must be ignored and return false, so a
    // plugin stays compatible with a host that sends more than it knows about.

    /** The app came to the foreground. No arguments. */
    String RESUME = "resume";

    /** The app is leaving the foreground. No arguments. */
    String PAUSE = "pause";

    /** Back was pressed. Return true to swallow it and stay open. */
    String BACK = "back";

    /**
     * A touch. Arguments: Integer action (a MotionEvent constant), Float x,
     * Float y -- centred and aspect-corrected to [-1,1], matching what
     * centred() returns in GLSL, so a coordinate can go straight to a shader
     * and land under the finger. Return true to claim it.
     */
    String TOUCH = "touch";

    /**
     * A second screen opened. Argument: the ViewGroup to fill. Return false and
     * the host closes it again, so a plugin that doesn't want one need do
     * nothing.
     */
    String SCREEN_OPEN = "screen.open";

    /** That second screen is going away. No arguments. */
    String SCREEN_CLOSE = "screen.close";

    /** The foreground service started. Argument: the String label it was given. */
    String SERVICE_START = "service.start";

    /** The foreground service is stopping. No arguments. */
    String SERVICE_STOP = "service.stop";

    /** What plugin code is handed. */
    interface Host {

        /**
         * The hosting activity: context, resources, window, system services,
         * startActivity, permission requests. The broad escape hatch -- a
         * plugin can reach anything an ordinary app could.
         */
        Activity activity();

        /**
         * A full-size container the plugin owns, laid over whatever the app
         * draws underneath. Add views here; the host empties it on detach, so
         * a swap never leaves a predecessor's UI behind.
         */
        ViewGroup container();

        /** Plugin-private storage. Survives swaps, process death and reinstalls. */
        File dataDir();

        /**
         * Scratch state the host holds, so it survives a swap -- a push builds
         * a new instance and every field resets, but this does not. Lives in
         * memory only; use dataDir() to outlive the process.
         */
        Bundle state();

        /** Append to the app's log ring, which push.sh reads back over HTTP. */
        void log(String message);

        void toast(String message);

        /** Run on the UI thread. */
        void post(Runnable action);

        /**
         * An optional host capability by name, or null if this host has none.
         *
         * How the app offers anything domain-specific without it being in this
         * interface. "gl" returns a {@link Gl}. A plugin that needs one should
         * check for null and degrade, rather than assume.
         */
        Object extension(String name);
    }

    /** Called once, on the UI thread, right after the dex is loaded. */
    void attach(Host host);

    /**
     * Called before the plugin is dropped. The host clears the container and
     * unregisters callbacks afterwards, so this is only for the plugin's own
     * cleanup -- threads, listeners, open files.
     */
    void detach();

    /**
     * Anything that is not attach or detach, keyed by name. Arguments depend on
     * the event; see the constants above.
     *
     * Unknown names must return false rather than throw.
     *
     * @return true if the event was handled, which matters for BACK and TOUCH
     */
    boolean event(String name, Object... args);

    /**
     * Free text from push.sh -c, answered synchronously, from the server
     * thread. The escape hatch in the other direction: a plugin exposes
     * whatever controls it likes without the app knowing anything about them.
     */
    String command(String line);
}
