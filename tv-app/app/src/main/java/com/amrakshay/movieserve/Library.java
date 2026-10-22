package com.amrakshay.movieserve;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The library as the server describes it, and the rows the list shows.
 *
 * <p>Kept free of Android types so it can be unit tested on the JVM: parsing someone else's JSON is
 * the part most likely to go wrong, and it should not need a TV to check.
 */
final class Library {

    /** One video. */
    static final class Item {
        final String name;
        final String folder;    // "" for a file at the top level
        final String path;
        final long size;
        final String url;       // relative to the server, e.g. /stream/Action/Film.mkv

        Item(String name, String folder, String path, long size, String url) {
            this.name = name;
            this.folder = folder;
            this.path = path;
            this.size = size;
            this.url = url;
        }

        /**
         * The address to hand VLC.
         *
         * <p>A file on the laptop is listed as a path like {@code /stream/Action/Film.mkv} and
         * needs the server's address in front of it. A link offered with {@code --video-url} is
         * already absolute and points somewhere else entirely, so it must be passed through
         * untouched -- gluing the server address onto it would produce nonsense.
         */
        String playbackUrl(String server) {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                return url;
            }
            return server + url;
        }

        /** "1.4 GB", for the line under the title. */
        String readableSize() {
            if (size <= 0) return "";
            if (size >= 1024L * 1024L * 1024L) {
                return String.format(java.util.Locale.US, "%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
            }
            return String.format(java.util.Locale.US, "%.0f MB", size / (1024.0 * 1024.0));
        }
    }

    /** A row in the list: either a folder heading or a video. */
    static final class Row {
        static final int HEADER = 0;
        static final int MOVIE = 1;

        final int kind;
        final String title;     // folder name, or the video's name
        final Item item;        // null for a header

        private Row(int kind, String title, Item item) {
            this.kind = kind;
            this.title = title;
            this.item = item;
        }

        static Row header(String title) {
            return new Row(HEADER, title, null);
        }

        static Row movie(Item item) {
            return new Row(MOVIE, item.name, item);
        }
    }

    private Library() {
    }

    /**
     * Read the server's /api/movies response.
     *
     * @throws JSONException if the body is not the shape this app expects, which the caller shows
     *                       as "that does not look like a movieserve server" rather than a crash.
     */
    static List<Item> parse(String body) throws JSONException {
        JSONObject root = new JSONObject(body);
        JSONArray movies = root.getJSONArray("movies");
        List<Item> items = new ArrayList<>(movies.length());
        for (int i = 0; i < movies.length(); i++) {
            JSONObject entry = movies.getJSONObject(i);
            String path = entry.getString("path");
            items.add(new Item(
                    entry.optString("name", path),
                    entry.optString("folder", ""),
                    path,
                    entry.optLong("size", 0L),
                    entry.getString("url")));
        }
        return items;
    }

    /**
     * Flatten the items into display rows, inserting a heading whenever the folder changes.
     *
     * <p>The server already sorts by folder then name, so one pass is enough; if it ever stops
     * doing that the worst case is a repeated heading, not a wrong list.
     */
    static List<Row> rows(List<Item> items) {
        List<Row> rows = new ArrayList<>();
        String current = null;
        for (Item item : items) {
            String folder = item.folder == null ? "" : item.folder;
            if (!folder.equals(current)) {
                current = folder;
                rows.add(Row.header(folder.isEmpty() ? "Movies" : folder));
            }
            rows.add(Row.movie(item));
        }
        return rows;
    }
}
