package com.gathin.tvlist;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Reads the two tabs over HTTPS and keeps the last good copy of each in the files dir, so the screen has something
 * to show at once and while the network is down. Nothing is ever sent but the request for the sheet.
 */
final class Sheet {
    static final long REFRESH_MS = 5 * 60_000L;
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_BYTES = 512 * 1024;
    static final String TODO_CACHE = "todo.csv";
    static final String TODAY_CACHE = "today.csv";

    /** The sheet answered with a sign-in page instead of data: it is not shared by link. */
    static final class NotSharedException extends IOException {
        NotSharedException() {
            super("not shared by link");
        }
    }

    private Sheet() {}

    /** Both tabs, saved to the cache; blocking, so call it off the main thread. */
    static void refresh(File dir, String id) throws IOException {
        String todo = fetch(Config.csvUrl(id, Config.TODO_TAB));
        String today = fetch(Config.csvUrl(id, Config.TODAY_TAB));
        Config.write(new File(dir, TODO_CACHE), todo);
        Config.write(new File(dir, TODAY_CACHE), today);
    }

    static String fetch(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", "cleanyourtv-tvlist/1.0 (Android TV screensaver)");
        try {
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN) {
                throw new NotSharedException();
            }
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
            String type = String.valueOf(connection.getContentType()).toLowerCase(Locale.ROOT);
            // A private sheet redirects to Google's sign-in page, which arrives as HTML with 200.
            if (type.contains("html")) throw new NotSharedException();
            try (InputStream in = connection.getInputStream()) {
                return read(in);
            }
        } finally {
            connection.disconnect();
        }
    }

    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
            if (out.size() > MAX_BYTES) throw new IOException("sheet too large");
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    static long cacheTime(File dir) {
        return new File(dir, TODO_CACHE).lastModified();
    }

    static void clearCache(File dir) {
        new File(dir, TODO_CACHE).delete();
        new File(dir, TODAY_CACHE).delete();
    }
}
