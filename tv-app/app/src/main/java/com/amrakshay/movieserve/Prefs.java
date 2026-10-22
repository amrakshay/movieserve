package com.amrakshay.movieserve;

import android.content.Context;
import android.content.SharedPreferences;

/** Where the server address is kept between runs. */
final class Prefs {

    private static final String FILE = "movieserve";
    private static final String KEY_SERVER = "server";

    private Prefs() {
    }

    private static SharedPreferences of(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** The saved base URL, e.g. "http://192.168.0.116:18080", or "" when never set. */
    static String server(Context context) {
        return of(context).getString(KEY_SERVER, "");
    }

    static void setServer(Context context, String value) {
        of(context).edit().putString(KEY_SERVER, normalise(value)).apply();
    }

    /**
     * Tidy what was typed on a TV keyboard: trim it, drop a trailing slash, and assume http when
     * no scheme is given, since that is what the server speaks.
     */
    static String normalise(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "";
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://" + value;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
