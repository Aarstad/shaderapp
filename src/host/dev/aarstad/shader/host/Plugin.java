package dev.aarstad.shader.host;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;

import java.io.File;

/**
 * The contract between the app and code pushed in at runtime. Frozen at
 * install, which is why only attach and detach are typed and everything else
 * is keyed by name through event() and Host.extension().
 */
public interface Plugin {

    // ---- event names ---------------------------------------------------------

    /** The app came to the foreground. No arguments. */
    String RESUME = "resume";

    /** The app is leaving the foreground. No arguments. */
    String PAUSE = "pause";

    /** Back was pressed. Return true to swallow it and stay open. */
    String BACK = "back";

    /** Integer action, Float x, Float y in [-1,1], matching GLSL centred(). */
    String TOUCH = "touch";

    /** Argument: the ViewGroup to fill. Return false and the host closes it. */
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
         * startActivity, permission requests.
         */
        Activity activity();

        /**
         * A full-size container the plugin owns, laid over whatever the app draws
         * underneath.
         */
        ViewGroup container();

        /** Plugin-private storage. Survives swaps, process death and reinstalls. */
        File dataDir();

        /**
         * Scratch state the host holds, so it survives a swap -- a push builds a new
         * instance and every field resets, but this does not.
         */
        Bundle state();

        /** Append to the app's log ring, which push.sh reads back over HTTP. */
        void log(String message);

        void toast(String message);

        /** Run on the UI thread. */
        void post(Runnable action);

        /** An optional host capability by name, or null if this host has none. */
        Object extension(String name);
    }

    /** Called once, on the UI thread, right after the dex is loaded. */
    void attach(Host host);

    /** Called before the plugin is dropped. */
    void detach();

    /** Keyed by name. Unknown names must return false, not throw. */
    boolean event(String name, Object... args);

    /** Free text from push.sh -c, answered synchronously, from the server thread. */
    String command(String line);
}
