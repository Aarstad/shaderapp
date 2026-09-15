package dev.aarstad.shader.host;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.FrameLayout;

/**
 * A spare activity, declared up front so a plugin can open a second screen
 * without a reinstall.
 *
 * This is what front-loading the manifest means in practice. Activities cannot
 * be added by a push -- they are fixed at install -- so the ones a plugin might
 * plausibly want have to exist before they are needed. It does nothing on its
 * own: it hands its container to the plugin and closes again if the plugin
 * isn't interested.
 */
public final class PluginScreen extends Activity {

    public static void open(Context ctx) {
        ctx.startActivity(new Intent(ctx, PluginScreen.class));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        HostActivity host = HostActivity.current();
        if (host == null) {
            // The host died while this was in the back stack; nothing to talk to.
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        setContentView(root);

        if (!host.plugins.event(Plugin.SCREEN_OPEN, root)) {
            // No plugin, or one that doesn't want a second screen. Don't leave
            // an empty activity sitting there.
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        HostActivity host = HostActivity.current();
        if (host != null) host.plugins.event(Plugin.SCREEN_CLOSE);
    }
}
