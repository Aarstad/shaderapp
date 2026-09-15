package dev.aarstad.shader.gl;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Where shader source comes from, and where pushed source goes.
 *
 * Two layers. The APK's assets/shaders/ holds the presets that ship with the
 * build; getFilesDir()/presets/ holds anything pushed over the loopback server
 * at runtime. A file in the overlay shadows the asset of the same name, so a
 * pushed shader survives the app being killed, and deleting the overlay file
 * reverts to the built-in.
 *
 * Every fragment shader is GLSL ES 1.00 and gets _head.glsl prepended, which
 * supplies u_res, u_time, centred() and palette(). That is what lets the
 * renderer swap between presets without special-casing any of them.
 */
public final class Presets {

    /** Name plus the *body* of the shader -- head is prepended at compile time. */
    static final class Preset {
        final String name;
        final String body;
        final boolean patched;

        Preset(String name, String body, boolean patched) {
            this.name = name;
            this.body = body;
            this.patched = patched;
        }
    }

    private static final String DIR = "shaders";
    private static final String HEAD_ASSET = DIR + "/_head.glsl";
    private static final String ORDER_ASSET = DIR + "/order.txt";

    /** Drawn when a shader won't compile, so a bad push can't leave a black screen. */
    static final String FALLBACK_BODY =
        "void main() {\n" +
        "    vec2 uv = centred();\n" +
        "    float w = 0.5 + 0.5 * sin(u_time * 3.0);\n" +
        "    float d = step(0.5, fract((uv.x + uv.y) * 2.0 + u_time * 0.5));\n" +
        "    gl_FragColor = vec4(mix(0.25, 0.75, d) * vec3(w, 0.05, 0.12), 1.0);\n" +
        "}\n";

    private Presets() {}

    static String overlayDir(Context ctx) {
        return new File(ctx.getFilesDir(), "presets").getAbsolutePath();
    }

    /**
     * Preset names are used as filenames and echoed into HTTP responses, so the
     * server only accepts this shape -- no dots, no separators, no traversal.
     */
    static boolean validName(String name) {
        if (name == null || name.isEmpty() || name.length() > 40) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                      || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    /** The preamble every shader body is compiled on top of. */
    static String head(Context ctx) {
        try {
            return readAsset(ctx.getAssets(), HEAD_ASSET);
        } catch (IOException e) {
            // Without the head nothing compiles, so fail loudly here rather
            // than as five identical "undefined centred()" logs later.
            throw new IllegalStateException("assets/" + HEAD_ASSET + " missing from the APK", e);
        }
    }

    /** How many lines the head adds, so compile errors can be mapped back to the file. */
    static int headLines(String head) {
        int n = 0;
        for (int i = 0; i < head.length(); i++) if (head.charAt(i) == '\n') n++;
        return n;
    }

    /**
     * Built-in order first, then anything in the overlay that isn't a built-in,
     * alphabetically. So pushing a brand new name appends it to the cycle and
     * pushing an existing one replaces it in place.
     */
    static List<Preset> load(Context ctx) {
        AssetManager assets = ctx.getAssets();
        File overlay = new File(overlayDir(ctx));

        LinkedHashSet<String> names = new LinkedHashSet<String>();
        try {
            for (String line : readAsset(assets, ORDER_ASSET).split("\n")) {
                String n = line.trim();
                if (!n.isEmpty() && validName(n)) names.add(n);
            }
        } catch (IOException e) {
            // No order file -- the overlay scan below still finds pushed presets.
        }

        String[] extra = overlay.list();
        if (extra != null) {
            Arrays.sort(extra);
            for (String f : extra) {
                if (!f.endsWith(".frag")) continue;
                String n = f.substring(0, f.length() - 5);
                if (validName(n)) names.add(n);
            }
        }

        List<Preset> out = new ArrayList<Preset>();
        for (String name : names) {
            File patched = new File(overlay, name + ".frag");
            if (patched.isFile()) {
                try {
                    out.add(new Preset(name, readFile(patched), true));
                    continue;
                } catch (IOException e) {
                    // Unreadable overlay file: fall through to the built-in.
                }
            }
            try {
                out.add(new Preset(name, readAsset(assets, DIR + "/" + name + ".frag"), false));
            } catch (IOException e) {
                // Named in order.txt but absent from assets, and no overlay
                // either. Nothing to draw, so leave it out of the cycle.
            }
        }

        if (out.isEmpty()) out.add(new Preset("Fallback", FALLBACK_BODY, false));
        return out;
    }

    /** Persist a pushed shader so it survives the process being killed. */
    static void save(Context ctx, String name, String body) throws IOException {
        File dir = new File(overlayDir(ctx));
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("could not create " + dir);
        }
        File tmp = new File(dir, name + ".frag.tmp");
        FileOutputStream os = new FileOutputStream(tmp);
        try {
            os.write(body.getBytes("UTF-8"));
        } finally {
            os.close();
        }
        File dest = new File(dir, name + ".frag");
        // Rename so a half-written file can never be picked up at next launch.
        if (!tmp.renameTo(dest)) {
            tmp.delete();
            throw new IOException("could not write " + dest);
        }
    }

    /** Drop a pushed shader. Returns false if there was nothing to revert. */
    static boolean revert(Context ctx, String name) {
        return new File(overlayDir(ctx), name + ".frag").delete();
    }

    /** @return the shader that ships in the APK under this name, or null. */
    static String builtInBody(Context ctx, String name) {
        try {
            return readAsset(ctx.getAssets(), DIR + "/" + name + ".frag");
        } catch (IOException e) {
            return null;
        }
    }

    private static String readAsset(AssetManager assets, String path) throws IOException {
        InputStream in = assets.open(path);
        try {
            return drain(in);
        } finally {
            in.close();
        }
    }

    private static String readFile(File f) throws IOException {
        InputStream in = new java.io.FileInputStream(f);
        try {
            return drain(in);
        } finally {
            in.close();
        }
    }

    private static String drain(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }
}
