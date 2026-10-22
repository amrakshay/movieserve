package com.amrakshay.movieserve;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** The one network call this app makes. */
final class Api {

    /** Long enough for a laptop waking its Wi-Fi, short enough to fail before the user does. */
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    private Api() {
    }

    /**
     * Fetch /api/movies from the given base URL.
     *
     * <p>Runs on a background thread; the caller posts the result back. Uses HttpURLConnection
     * rather than a client library so the app keeps no dependencies it does not need.
     *
     * @throws IOException on anything the user needs telling about: laptop asleep, wrong address,
     *                     server not running, or an HTTP error from something else on that port.
     */
    static String movies(String baseUrl) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + "/api/movies").openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("server answered HTTP " + status);
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                return out.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }
}
