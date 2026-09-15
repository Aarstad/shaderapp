package dev.aarstad.shader.plugin;

import android.view.MotionEvent;

import dev.aarstad.shader.Plugin;

/**
 * The example plugin: everything here is pushed as dex and swapped into the
 * running app, so any of it can change without a reinstall.
 *
 * It does three things the APK has no idea about -- feeds the shaders a touch
 * position and a decaying tap envelope, optionally auto-cycles presets, and
 * exposes both over command(). None of that required a line of host code;
 * uniform names and command syntax are invented on this side of the interface.
 */
public final class Main implements Plugin {

    private Host host;

    /** Last touch, already in shader space. */
    private float tx, ty;
    private boolean down;

    /** When the last press landed, on the shader clock. */
    private float tappedAt = -99f;

    /** How fast the tap envelope decays; e-folds per second. */
    private float decay = 1.6f;

    /** Seconds between automatic preset changes; 0 is off. */
    private float cycleEvery = 0f;
    private float cycledAt = 0f;

    @Override
    public void attach(Host host) {
        this.host = host;
        host.log("touchwarp up, " + host.presetCount() + " presets");
        host.toast("plugin: touchwarp");
    }

    @Override
    public void frame(float t) {
        // Fed every frame regardless of which shader is drawing. Shaders that
        // don't declare these simply never see them.
        host.setUniform("u_touch", tx, ty);
        host.setFloat("u_pulse", (float) Math.exp(-Math.max(0f, t - tappedAt) * decay));
        host.setFloat("u_down", down ? 1f : 0f);

        if (cycleEvery > 0f && t - cycledAt >= cycleEvery) {
            cycledAt = t;
            int n = host.presetCount();
            if (n > 0) host.select((host.currentPreset() + 1) % n);
        }
    }

    @Override
    public boolean touch(int action, float x, float y) {
        tx = x;
        ty = y;
        if (action == MotionEvent.ACTION_DOWN) {
            down = true;
            tappedAt = host.seconds();
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            down = false;
        }
        // Not claiming the touch, so tap-to-cycle still works underneath.
        return false;
    }

    @Override
    public String command(String line) {
        String[] arg = line.trim().split("\\s+");
        String verb = arg.length > 0 ? arg[0] : "";

        if (verb.equals("cycle")) {
            if (arg.length > 1 && arg[1].equals("off")) {
                cycleEvery = 0f;
                return "auto-cycle off";
            }
            if (arg.length > 1) {
                try {
                    cycleEvery = Float.parseFloat(arg[1]);
                    cycledAt = host.seconds();
                    return "auto-cycle every " + cycleEvery + "s";
                } catch (NumberFormatException e) {
                    return "cycle wants seconds, or 'off'";
                }
            }
            return "cycle " + (cycleEvery > 0f ? cycleEvery + "s" : "off");
        }

        if (verb.equals("decay")) {
            if (arg.length > 1) {
                try {
                    decay = Float.parseFloat(arg[1]);
                    return "decay " + decay;
                } catch (NumberFormatException e) {
                    return "decay wants a number";
                }
            }
            return "decay " + decay;
        }

        // Fire the tap envelope without anyone touching the screen. This verb
        // did not exist in the dex that was running a minute ago.
        if (verb.equals("burst")) {
            if (arg.length > 2) {
                try {
                    tx = Float.parseFloat(arg[1]);
                    ty = Float.parseFloat(arg[2]);
                } catch (NumberFormatException e) {
                    return "burst wants two numbers in [-1,1], or nothing";
                }
            }
            tappedAt = host.seconds();
            host.toast("burst");
            return "burst at " + tx + "," + ty;
        }

        if (verb.equals("status")) {
            return "touchwarp/2  touch " + tx + "," + ty
                + "  down " + down
                + "  decay " + decay
                + "  cycle " + (cycleEvery > 0f ? cycleEvery + "s" : "off")
                + "  preset " + host.presetName(host.currentPreset());
        }

        return "commands: cycle <s>|off, decay <n>, burst [x y], status";
    }

    @Override
    public void detach() {
        host.log("touchwarp down");
    }
}
