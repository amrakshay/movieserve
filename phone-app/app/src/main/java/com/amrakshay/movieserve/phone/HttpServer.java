package com.amrakshay.movieserve.phone;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The phone's half of movieserve: a small HTTP server speaking exactly the same two endpoints as
 * {@code server/movieserve.py}, so the TV app cannot tell a phone from a laptop.
 *
 * <pre>
 *   GET /api/movies     the library as JSON
 *   GET /stream/&lt;uri&gt;   one video, with range support
 * </pre>
 *
 * <p>Written against {@link ServerSocket} rather than pulling in a web framework: the whole surface
 * is two routes, and a dependency here would be most of the app's size.
 *
 * <p>There is no authentication, by design. Anyone on the same network can list and fetch the
 * shared folder, which is the trade-off this project documents rather than hides.
 */
final class HttpServer {

    private static final String TAG = "movieserve";
    /** Big enough to keep a busy link fed, small enough that a seek abandons little work. */
    private static final int CHUNK = 256 * 1024;
    private static final String CRLF = "\r\n";

    private final Context context;
    private final int port;
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** One thread per connection. A player opens a couple; a browser poking about may open more. */
    private final ExecutorService workers = Executors.newCachedThreadPool();

    private ServerSocket socket;
    private volatile Library library;

    HttpServer(Context context, int port, Library library) {
        this.context = context.getApplicationContext();
        this.port = port;
        this.library = library;
    }

    /** Swap the library when the owner picks a different folder, without restarting the server. */
    void setLibrary(Library next) {
        this.library = next;
    }

    boolean isRunning() {
        return running.get();
    }

    /** Binds the port. Throws if something else already holds it, which the caller shows. */
    void start() throws IOException {
        if (running.get()) {
            return;
        }
        socket = new ServerSocket(port);
        running.set(true);
        Thread accepter = new Thread(this::acceptLoop, "movieserve-accept");
        accepter.setDaemon(true);
        accepter.start();
    }

    void stop() {
        running.set(false);
        try {
            if (socket != null) {
                socket.close();     // unblocks accept()
            }
        } catch (IOException ignored) {
            // Closing a socket that is already gone is not worth reporting.
        }
        workers.shutdownNow();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = socket.accept();
                workers.execute(() -> handle(client));
            } catch (IOException e) {
                if (running.get()) {
                    Log.w(TAG, "accept failed", e);
                }
                // Otherwise stop() closed the socket, which is how this loop is meant to end.
            }
        }
    }

    private void handle(Socket client) {
        try {
            client.setTcpNoDelay(true);
            InputStream in = client.getInputStream();
            OutputStream out = new BufferedOutputStream(client.getOutputStream(), 32 * 1024);

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return;
            }
            String method = parts[0];
            String target = parts[1];

            String rangeHeader = null;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Range")) {
                    rangeHeader = line.substring(colon + 1).trim();
                }
            }

            boolean body = "GET".equalsIgnoreCase(method);
            if (!body && !"HEAD".equalsIgnoreCase(method)) {
                send(out, 405, "Method Not Allowed", "application/json",
                        "{\"error\":\"only GET and HEAD\"}".getBytes(StandardCharsets.UTF_8), true);
                return;
            }

            int query = target.indexOf('?');
            String path = query < 0 ? target : target.substring(0, query);

            if ("/api/movies".equals(path)) {
                List<Library.Entry> entries = library.entries();
                byte[] json = library.toJson(entries).getBytes(StandardCharsets.UTF_8);
                send(out, 200, "OK", "application/json; charset=utf-8", json, body);
            } else if (path.startsWith("/stream/")) {
                stream(out, decode(path.substring("/stream/".length())), rangeHeader, body);
            } else if ("/".equals(path) || "/health".equals(path)) {
                send(out, 200, "OK", "text/plain; charset=utf-8",
                        "movieserve is running\n".getBytes(StandardCharsets.UTF_8), body);
            } else {
                send(out, 404, "Not Found", "application/json",
                        "{\"error\":\"no such path\"}".getBytes(StandardCharsets.UTF_8), body);
            }
            out.flush();
        } catch (IOException e) {
            // A player closing the connection mid-stream is routine: it happens on every seek,
            // pause and stop. Nothing to report.
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
                // Already gone.
            }
        }
    }

    // ---- streaming ---------------------------------------------------------------------------

    private void stream(OutputStream out, String uriString, String rangeHeader, boolean body)
            throws IOException {
        Uri uri = findInLibrary(uriString);
        if (uri == null) {
            // Only files the library currently lists can be fetched, so a stale or invented URI
            // cannot be used to read something outside the folder that was shared.
            send(out, 404, "Not Found", "application/json",
                    "{\"error\":\"no such video\"}".getBytes(StandardCharsets.UTF_8), body);
            return;
        }

        ContentResolver resolver = context.getContentResolver();
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r")) {
            if (descriptor == null) {
                send(out, 404, "Not Found", "application/json",
                        "{\"error\":\"cannot open\"}".getBytes(StandardCharsets.UTF_8), body);
                return;
            }
            long total = descriptor.getStatSize();
            Range.Result wanted = Range.parse(rangeHeader, total);

            if (wanted.unsatisfiable) {
                StringBuilder head = new StringBuilder();
                head.append("HTTP/1.1 416 Range Not Satisfiable").append(CRLF);
                head.append("Content-Range: bytes */").append(total).append(CRLF);
                head.append("Content-Length: 0").append(CRLF);
                head.append("Accept-Ranges: bytes").append(CRLF).append(CRLF);
                out.write(head.toString().getBytes(StandardCharsets.UTF_8));
                return;
            }

            long start = wanted.isPartial() ? wanted.range.start : 0;
            long end = wanted.isPartial() ? wanted.range.end : total - 1;
            long length = end - start + 1;

            StringBuilder head = new StringBuilder();
            head.append("HTTP/1.1 ").append(wanted.isPartial() ? "206 Partial Content" : "200 OK").append(CRLF);
            head.append("Content-Type: ").append(contentType(uriString)).append(CRLF);
            head.append("Content-Length: ").append(length).append(CRLF);
            head.append("Accept-Ranges: bytes").append(CRLF);
            if (wanted.isPartial()) {
                head.append("Content-Range: bytes ").append(start).append('-').append(end)
                        .append('/').append(total).append(CRLF);
            }
            head.append("Connection: keep-alive").append(CRLF).append(CRLF);
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));
            if (!body) {
                return;
            }

            // A plain InputStream would have to be read and discarded up to `start`, which makes a
            // seek near the end of a film take as long as reading the film. The file descriptor's
            // channel can be positioned directly.
            try (FileInputStream file = new FileInputStream(descriptor.getFileDescriptor())) {
                file.getChannel().position(start);
                byte[] buffer = new byte[CHUNK];
                long remaining = length;
                while (remaining > 0) {
                    int read = file.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) {
                        break;
                    }
                    out.write(buffer, 0, read);
                    remaining -= read;
                }
                out.flush();
            }
        }
    }

    /**
     * The content URI for a /stream/ path, but only if the library lists it.
     *
     * <p>Checking against the current listing is what keeps this from being a way to read any file
     * the app happens to have permission for.
     */
    private Uri findInLibrary(String uriString) {
        for (Library.Entry entry : library.entries()) {
            if (entry.content != null && entry.content.toString().equals(uriString)) {
                return entry.content;
            }
        }
        return null;
    }

    private static String contentType(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".ts")) return "video/mp2t";
        return "video/x-matroska";      // .mkv, and a reasonable default for anything else listed
    }

    // ---- plumbing ----------------------------------------------------------------------------

    private static void send(OutputStream out, int status, String reason, String type,
                             byte[] payload, boolean body) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append(CRLF);
        head.append("Content-Type: ").append(type).append(CRLF);
        head.append("Content-Length: ").append(payload.length).append(CRLF);
        head.append("Accept-Ranges: bytes").append(CRLF);
        head.append("Connection: keep-alive").append(CRLF).append(CRLF);
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        if (body) {
            out.write(payload);
        }
    }

    /** Read one CRLF-terminated line without buffering past it, which would eat the body. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                line.append((char) c);
            }
            if (line.length() > 8192) {
                break;      // a request line this long is not one we are going to serve
            }
        }
        if (c == -1 && line.length() == 0) {
            return null;
        }
        return line.toString();
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}
