package com.amrakshay.movieserve;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Which apps on this TV can play a video URL.
 *
 * <p>This app deliberately plays nothing itself, so the only question is who to hand the URL to.
 * Asking Android rather than naming one player means whatever the owner already has — VLC, MX
 * Player, the set's own player — works, instead of one hard-coded name working and everything else
 * being told to install something.
 *
 * <p>The filtering is kept free of Android types in {@link #keep} so it can be unit tested: the
 * decisions about what is and is not a real player are where this can quietly go wrong.
 */
final class Players {

    /**
     * Packages that answer the intent without being players.
     *
     * <p>{@code frameworkpackagestubs} is Android's own placeholder: it claims the intent and then
     * tells the user nothing can handle it. Offering it in a list of players would be a trap, since
     * it looks like a choice and always fails.
     */
    private static final List<String> NOT_PLAYERS = java.util.Arrays.asList(
            "com.google.android.tv.frameworkpackagestubs",
            "com.android.fallback");

    /** One app that can play a video. */
    static final class Player {
        final String label;
        final String packageName;
        final String activityName;

        Player(String label, String packageName, String activityName) {
            this.label = label;
            this.packageName = packageName;
            this.activityName = activityName;
        }
    }

    private Players() {
    }

    /** A representative intent: an HTTP video URL, which is all this app ever opens. */
    static Intent viewIntent(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.parse(url), "video/*");
        return intent;
    }

    /**
     * Every app that can play a video URL, sorted by name, minus the ones that only pretend to.
     *
     * @param sampleUrl any URL of the kind that will be played; only its shape matters
     */
    static List<Player> available(Context context, String sampleUrl) {
        PackageManager packages = context.getPackageManager();
        List<ResolveInfo> matches = packages.queryIntentActivities(viewIntent(sampleUrl), 0);
        List<Player> players = new ArrayList<>();
        for (ResolveInfo match : matches) {
            if (match.activityInfo == null) {
                continue;
            }
            String packageName = match.activityInfo.packageName;
            if (!keep(packageName)) {
                continue;
            }
            CharSequence label = match.loadLabel(packages);
            players.add(new Player(
                    label == null ? packageName : label.toString(),
                    packageName,
                    match.activityInfo.name));
        }
        Collections.sort(players, new Comparator<Player>() {
            @Override
            public int compare(Player a, Player b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return players;
    }

    /** Whether a package that claims the intent is actually worth offering. */
    static boolean keep(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        // Compared case-insensitively on both sides: an entry written with capitals here would
        // otherwise never match a lowercased package name and would silently do nothing.
        String lower = packageName.toLowerCase(Locale.US);
        for (String excluded : NOT_PLAYERS) {
            if (lower.equals(excluded.toLowerCase(Locale.US))) {
                return false;
            }
        }
        // Our own app resolves nothing here today, but excluding it keeps a future intent filter
        // from putting movieserve in its own list of players.
        return !lower.equals("com.amrakshay.movieserve");
    }

    /** The saved player if it is still installed, otherwise null so the caller asks again. */
    static Player saved(Context context, String sampleUrl) {
        String wanted = Prefs.player(context);
        if (wanted.isEmpty()) {
            return null;
        }
        for (Player player : available(context, sampleUrl)) {
            if (player.packageName.equals(wanted)) {
                return player;
            }
        }
        return null;    // uninstalled since it was chosen
    }
}
