package dev.aarstad.shader.host;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * A deliberately small HTTP server, bound to loopback only, that accepts code,
 * data and commands for the running app.
 *
 * This is the app's whole update story. An APK can't replace itself without the
 * package installer prompting, but a dex can be loaded at runtime and files can
 * be written at runtime -- so the APK ships a host, and iteration happens by
 * POSTing into the running process. No reinstall, no prompt, and the app does
 * not restart.
 *
 * Nothing here knows what the app draws. Core routes cover the plugin, the
 * command channel and plugin-visible files; anything app-specific arrives
 * through {@link Extra}, which the shader module uses for its preset routes.
 *
 * It listens on 127.0.0.1, so only code already on this device can reach it,
 * and only while the activity is in the foreground. Any app on the device could
 * post to it -- the right trade for a development channel, and the reason it
 * closes the moment the app leaves the foreground.
 */
public final class PushServer implements Runnable {

    /** First port tried; push.sh probes this range upwards to find us. */
    static final int PORT_BASE = 8777;
    static final int PORT_TRIES = 10;

    private static final int MAX_BODY = 4 * 1024 * 1024;
    private static final int READ_TIMEOUT_MS = 5000;

    /** A reply, and the status it should go back with. */
    public static final class Result {
        public final boolean ok;
        public final String log;
        public final int status;

        public Result(boolean ok, String log) {
            this(ok, log, ok ? 200 : 400);
        }

        public Result(boolean ok, String log, int status) {
            this.ok = ok;
            this.log = log == null ? "" : log;
            this.status = status;
        }
    }

    /** App-specific routes, bolted on without the server knowing what they mean. */
    public interface Extra {
        /** @return a reply, or null to fall through to the 404 help text */
        Result handle(String method, String path, byte[] body);

        /** Lines for the 404 help, describing what this adds. */
        String help();
    }

    /** What the server is allowed to ask of the running app. */
    public interface Bridge {
        Result installPlugin(byte[] dex, String className);
        Result dropPlugin();
        String pluginStatus();
        Result command(String line);

        /** Where POST /file writes; also what a plugin sees as its data dir. */
        File dataDir();

        /** Extra lines for /health, or "". */
        String info();

        /** App-specific routes, or null. */
        Extra extra();
    }

    private final Bridge bridge;
    private final String version;
    private volatile ServerSocket socket;
    private volatile boolean running;
    private Thread thread;

    public PushServer(Bridge bridge, String version) {
        this.bridge = bridge;
        this.version = version;
    }

    /**
     * Loopback, but specifically the IPv4 one.
     *
     * InetAddress.getLoopbackAddress() hands back ::1 on any device with IPv6
     * up, and a socket bound to ::1 will not accept the IPv4 connections that a
     * client aimed at 127.0.0.1 makes -- it just reads as "connection refused",
     * which looks exactly like the app not running.
     */
    private static InetAddress loopback() {
        try {
            return InetAddress.getByName("127.0.0.1");
        } catch (UnknownHostException e) {
            return InetAddress.getLoopbackAddress();
        }
    }

    /**
     * Names are used as filenames and echoed into responses, so only this
     * shape is accepted -- no dots, no separators, nothing that traverses.
     */
    public static boolean validName(String name) {
        if (name == null || name.isEmpty() || name.length() > 64) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                      || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            if (!ok) return false;
        }
        // A leading dot, or any "..", would climb out of the directory.
        return !name.startsWith(".") && !name.contains("..");
    }

    /** @return the bound port, or -1 if every candidate was taken. */
    public int start() {
        InetAddress loopback = loopback();
        for (int i = 0; i < PORT_TRIES; i++) {
            try {
                socket = new ServerSocket(PORT_BASE + i, 4, loopback);
            } catch (IOException e) {
                continue;
            }
            running = true;
            thread = new Thread(this, "push-server");
            thread.setDaemon(true);
            thread.start();
            return PORT_BASE + i;
        }
        return -1;
    }

    public void stop() {
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

        // Read as bytes throughout. Most bodies are text, but a pushed dex is
        // binary and would not survive a round trip through a String.
        byte[] body = length > 0 ? readBody(in, length) : new byte[0];
        route(out, method, path, body, pluginClass);
    }

    private void route(OutputStream out, String method, String path, byte[] raw, String pluginClass)
            throws IOException {
        String body = raw.length == 0 ? "" : new String(raw, "UTF-8");

        if (path.equals("/health") && method.equals("GET")) {
            String info = bridge.info();
            respond(out, 200, "app " + version
                + "\n" + bridge.pluginStatus()
                + (info == null || info.isEmpty() ? "" : "\n" + info));
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
                send(out, bridge.installPlugin(raw, pluginClass));
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

        // Files a plugin can read: push data, not just code.
        if (path.equals("/files") && method.equals("GET")) {
            send(out, listFiles());
            return;
        }
        if (path.startsWith("/file/")) {
            String name = decode(path.substring("/file/".length()));
            if (!validName(name)) {
                respond(out, 400, "bad file name: letters, digits, _ - . only\n");
                return;
            }
            if (method.equals("POST") || method.equals("PUT")) {
                send(out, writeFile(name, raw));
                return;
            }
            if (method.equals("DELETE")) {
                boolean gone = new File(bridge.dataDir(), name).delete();
                respond(out, gone ? 200 : 404,
                    gone ? "deleted " + name + "\n" : "no such file: " + name + "\n");
                return;
            }
        }

        Extra extra = bridge.extra();
        if (extra != null) {
            Result r = extra.handle(method, path, raw);
            if (r != null) {
                send(out, r);
                return;
            }
        }

        respond(out, 404, "no such endpoint\n\n"
            + "GET    /health\n"
            + "GET    /plugin          plugin status and recent log\n"
            + "POST   /plugin          body: a dex file; X-Plugin-Class optional\n"
            + "DELETE /plugin          detach it\n"
            + "POST   /command         body: free text for the plugin\n"
            + "GET    /files           list files the plugin can read\n"
            + "POST   /file/<name>     body: anything; lands in the plugin's data dir\n"
            + "DELETE /file/<name>     remove it\n"
            + (extra == null ? "" : extra.help()));
    }

    private Result listFiles() {
        File[] all = bridge.dataDir().listFiles();
        if (all == null || all.length == 0) return new Result(true, "no files\n");
        Arrays.sort(all, (a, b) -> a.getName().compareTo(b.getName()));
        StringBuilder sb = new StringBuilder();
        for (File f : all) {
            sb.append(String.format("%8d  %s%n", f.length(), f.getName()));
        }
        return new Result(true, sb.toString());
    }

    private Result writeFile(String name, byte[] data) {
        File dir = bridge.dataDir();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return new Result(false, "could not create " + dir + "\n", 500);
        }
        // Write then rename, so a plugin can never read a half-written file.
        File tmp = new File(dir, name + ".part");
        try {
            FileOutputStream os = new FileOutputStream(tmp);
            try {
                os.write(data);
            } finally {
                os.close();
            }
            File dest = new File(dir, name);
            if (!tmp.renameTo(dest)) {
                tmp.delete();
                return new Result(false, "could not write " + name + "\n", 500);
            }
        } catch (IOException e) {
            tmp.delete();
            return new Result(false, "write failed: " + e + "\n", 500);
        }
        return new Result(true, "wrote " + data.length + " bytes to " + name + "\n");
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
        return Arrays.copyOf(data, read);
    }

    private static void send(OutputStream out, Result r) throws IOException {
        respond(out, r.status, r.log);
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
            case 500: return "Internal Server Error";
            case 503: return "Service Unavailable";
            default:  return "Status";
        }
    }
}
