package com.amrakshay.movieserve.phone;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/** The folder that was picked and the optional web link, remembered between runs. */
final class Prefs {

    static final int PORT = 18080;

    private static final String FILE = "movieserve-phone";
    private static final String KEY_TREE = "tree";
    private static final String KEY_URL = "video_url";

    private Prefs() {
    }

    private static SharedPreferences of(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** The Storage Access Framework tree, or null if no folder has been chosen. */
    static Uri tree(Context context) {
        String value = of(context).getString(KEY_TREE, "");
        return value.isEmpty() ? null : Uri.parse(value);
    }

    static void setTree(Context context, Uri uri) {
        of(context).edit().putString(KEY_TREE, uri == null ? "" : uri.toString()).apply();
    }

    static String videoUrl(Context context) {
        return of(context).getString(KEY_URL, "");
    }

    static void setVideoUrl(Context context, String url) {
        of(context).edit().putString(KEY_URL, normalise(url)).apply();
    }

    /** Trim it, and refuse anything a player could not be handed. */
    static String normalise(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://" + value;
        }
        return value;
    }

    /**
     * Whether this is a link worth offering. Only the shape is checked: whether the far end really
     * serves video cannot be known without fetching it, and a link to a web page rather than a file
     * will not play.
     */
    static boolean isPlayableUrl(String url) {
        if (url == null) {
            return false;
        }
        String value = url.trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return false;
        }
        Uri parsed = Uri.parse(value);
        return parsed.getHost() != null && !parsed.getHost().isEmpty();
    }
}
