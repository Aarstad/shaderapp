package dev.aarstad.shader;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.UnknownHostException;

/**
 * A deliberately small HTTP server, bound to loopback only, that accepts shader
 * source and hands it to the renderer to compile in place.
 *
 * This is the whole point of the app's update story: an APK can't replace
 * itself without the package installer prompting, but GLSL is just text that
 * the driver compiles at runtime. So the APK ships a loader, and iteration
 * happens by POSTing source into the running process -- no reinstall, no
 * prompt, and the shader changes without the app restarting.
 *
 * It listens on 127.0.0.1, so only code already on this device can reach it,
 * and it only runs while the activity is in the foreground. That is the right
 * amount of exposure for a development channel: any app on the device could
 * post to it, but the worst it can do is draw something.
 */
final class ShaderServer implements Runnable {

    /** First port tried; push.sh probes this range upwards to find us. */
    static final int PORT_BASE = 8777;
    static final int PORT_TRIES = 10;

    private static final int MAX_BODY = 4 * 1024 * 1024;
    private static final int READ_TIMEOUT_MS = 5000;

    /** Outcome of a compile attempt on the GL thread. */
    static final class Result {
        final boolean ok;
        final String log;

        Result(boolean ok, String log) {
            this.ok = ok;
            this.log = log == null ? "" : log;
        }
    }

    /** What the server is allowed to ask of the running app. */
    interface Bridge {
        /** Compiles and swaps in a shader. Blocks until the GL thread answers. */
        Result install(String name, String body);

        /** Drops a pushed shader, restoring the built-in. */
        Result revert(String name);

        /** Switches the displayed preset by name or index. */
        Result select(String which);

        /** Names in cycle order, current one marked. */
        String describe();

        /** Loads a pushed dex and attaches the plugin it contains. */
        Result installPlugin(byte[] dex, String className);

        /** Detaches the plugin and stops reloading it at launch. */
        Result dropPlugin();

        /** One line describing the plugin, plus its recent log. */
        String pluginStatus();

        /** Free text through to the plugin's command(). */
        Result command(String line);
    }

    private final Bridge bridge;
    private final int headLines;
    private final String version;
    private volatile ServerSocket socket;
    private volatile boolean running;
    private Thread thread;

    ShaderServer(Bridge bridge, int headLines, String version) {
        this.bridge = bridge;
        this.headLines = headLines;
        this.version = version;
    }

    /**
     * Loopback, but specifically the IPv4 one.
     *
     * InetAddress.getLoopbackAddress() hands back ::1 on any device with IPv6
     * up, and a socket bound to ::1 will not accept the IPv4 connections that
     * a client aimed at 127.0.0.1 makes -- it just reads as "connection
     * refused", which looks exactly like the app not running.
     */
    private static InetAddress loopback() {
        try {
            return InetAddress.getByName("127.0.0.1");
        } catch (UnknownHostException e) {
            return InetAddress.getLoopbackAddress();
        }
    }

    /** @return the bound port, or -1 if every candidate was taken. */
    int start() {
        InetAddress loopback = loopback();
        for (int i = 0; i < PORT_TRIES; i++) {
            try {
                socket = new ServerSocket(PORT_BASE + i, 4, loopback);
            } catch (IOException e) {
                continue;
            }
            running = true;
            thread = new Thread(this, "shader-server");
            thread.setDaemon(true);
            thread.start();
            return PORT_BASE + i;
        }
        return -1;
    }

    void stop() {
        running = false;
        ServerSocket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // Closing is what unblocks accept(); nothing to recover from.
            }
        }
    }

    @Override
    public void run() {
        while (running) {
            Socket client = null;
            try {
                client = socket.accept();
                client.setSoTimeout(READ_TIMEOUT_MS);
                handle(client);
            } catch (IOException e) {
                // A closed listener during stop() lands here, as does any
                // client that hangs up mid-request. Neither is worth logging.
            } finally {
                if (client != null) {
                    try {
                        client.close();
                    } catch (IOException ignored) { }
                }
            }
        }
    }

    private void handle(Socket client) throws IOException {
        InputStream in = new BufferedInputStream(client.getInputStream());
        OutputStream out = client.getOutputStream();

        String requestLine = readLine(in);
        if (requestLine == null) return;

        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            respond(out, 400, "malformed request line\n");
            return;
        }
        String method = parts[0];
        String path = parts[1];

        int length = 0;
        String pluginClass = null;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (key.equalsIgnoreCase("Content-Length")) {
                try {
                    length = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    respond(out, 400, "bad Content-Length\n");
                    return;
                }
            } else if (key.equalsIgnoreCase("X-Plugin-Class")) {
                pluginClass = value;
            }
        }
        if (length > MAX_BODY) {
            respond(out, 413, "body too large (" + length + " bytes, max " + MAX_BODY + ")\n");
            return;
        }

        // Read as bytes throughout. Shader source is text, but a pushed dex is
        // binary and would not survive a round trip through a String.
        byte[] body = length > 0 ? readBody(in, length) : new byte[0];
        route(out, method, path, body, pluginClass);
    }

    private void route(OutputStream out, String method, String path, byte[] raw, String pluginClass)
            throws IOException {
        String body = raw.length == 0 ? "" : new String(raw, "UTF-8");

        if (path.equals("/health") && method.equals("GET")) {
            respond(out, 200, "shader " + version
                + "\nheadLines " + headLines
                + "\n" + bridge.pluginStatus()
                + "\n" + bridge.describe());
            return;
        }
        if (path.equals("/plugin")) {
            if (method.equals("GET")) {
                respond(out, 200, bridge.pluginStatus() + "\n");
                return;
            }
            if (method.equals("POST") || method.equals("PUT")) {
                if (raw.length == 0) {
                    respond(out, 400, "empty body -- nothing to load\n");
                    return;
                }
                Result r = bridge.installPlugin(raw, pluginClass);
                respond(out, r.ok ? 200 : 400, r.log);
                return;
            }
            if (method.equals("DELETE")) {
                Result r = bridge.dropPlugin();
                respond(out, r.ok ? 200 : 404, r.log);
                return;
            }
        }
        if (path.equals("/command") && method.equals("POST")) {
            Result r = bridge.command(body);
            respond(out, r.ok ? 200 : 404, r.log);
            return;
        }
        if (path.equals("/presets") && method.equals("GET")) {
            respond(out, 200, bridge.describe());
            return;
        }
        if (path.startsWith("/preset/")) {
            String name = decode(path.substring("/preset/".length()));
            if (!Presets.validName(name)) {
                respond(out, 400, "bad preset name: letters, digits, _ and - only\n");
                return;
            }
            if (method.equals("POST") || method.equals("PUT")) {
                if (body.trim().isEmpty()) {
                    respond(out, 400, "empty body -- nothing to compile\n");
                    return;
                }
                Result r = bridge.install(name, body);
                respond(out, r.ok ? 200 : 400, r.log);
                return;
            }
            if (method.equals("DELETE")) {
                Result r = bridge.revert(name);
                respond(out, r.ok ? 200 : 404, r.log);
                return;
            }
        }
        if (path.startsWith("/select/") && method.equals("POST")) {
            Result r = bridge.select(decode(path.substring("/select/".length())));
            respond(out, r.ok ? 200 : 404, r.log);
            return;
        }
        respond(out, 404, "no such endpoint\n\n"
            + "GET    /health\n"
            + "GET    /presets\n"
            + "POST   /preset/<Name>   body: fragment shader source\n"
            + "DELETE /preset/<Name>   revert to the built-in\n"
            + "POST   /select/<Name>   switch the displayed preset\n"
            + "GET    /plugin          plugin status and recent log\n"
            + "POST   /plugin          body: a dex file; X-Plugin-Class optional\n"
            + "DELETE /plugin          detach it\n"
            + "POST   /command         body: free text for the plugin\n");
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** Reads one CRLF- or LF-terminated line. Returns null at end of stream. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') buf.write(c);
            if (buf.size() > 8192) throw new IOException("header line too long");
        }
        if (c == -1 && buf.size() == 0) return null;
        return buf.toString("UTF-8");
    }

    private static byte[] readBody(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(data, read, length - read);
            if (n < 0) break;
            read += n;
        }
        if (read == length) return data;
        byte[] cut = new byte[read];
        System.arraycopy(data, 0, cut, 0, read);
        return cut;
    }

    private static void respond(OutputStream out, int code, String body) throws IOException {
        byte[] payload = body.getBytes("UTF-8");
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
            + "Content-Type: text/plain; charset=utf-8\r\n"
            + "Content-Length: " + payload.length + "\r\n"
            + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(payload);
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 413: return "Payload Too Large";
            case 503: return "Service Unavailable";
            default:  return "Status";
        }
    }
}
