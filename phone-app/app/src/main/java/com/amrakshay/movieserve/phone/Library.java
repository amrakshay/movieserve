package com.amrakshay.movieserve.phone;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * What the phone offers: the videos under a folder the owner picked, and one optional web link.
 *
 * <p>The folder arrives as a Storage Access Framework tree rather than a path, because a modern
 * Android app cannot simply read {@code /sdcard/Movies}. That means files are addressed by content
 * URI, so a stream URL carries the document's id rather than a filesystem path.
 *
 * <p>The JSON produced here is deliberately identical to the Python server's, so the TV app cannot
 * tell the difference between a laptop and a phone.
 */
final class Library {

    static final String VERSION = "1.0.0";

    /** Matches the Python server. Anything else in the folder is ignored rather than listed. */
    private static final List<String> VIDEO_SUFFIXES =
            Arrays.asList(".mkv", ".mp4", ".avi", ".mov", ".m4v", ".webm", ".ts");

    /** One video, either on the phone or on the web. */
    static final class Entry {
        final String name;
        final String folder;    // "" at the top level, "Link" for the web link
        final String path;
        final long size;
        final String url;
        final Uri content;      // null for the web link

        Entry(String name, String folder, String path, long size, String url, Uri content) {
            this.name = name;
            this.folder = folder;
            this.path = path;
            this.size = size;
            this.url = url;
            this.content = content;
        }
    }

    private final Context context;
    private final Uri tree;         // may be null: a link on its own is a valid library
    private final String videoUrl;  // may be null

    Library(Context context, Uri tree, String videoUrl) {
        this.context = context.getApplicationContext();
        this.tree = tree;
        this.videoUrl = videoUrl == null || videoUrl.trim().isEmpty() ? null : videoUrl.trim();
    }

    /**
     * Every video on offer, folders before files, the web link last.
     *
     * <p>Walked fresh each time rather than cached, so a video copied onto the phone while the
     * server runs appears without restarting it. A Storage Access Framework walk costs one query
     * per directory, which is fine for a folder of films and would not be for a whole SD card.
     */
    List<Entry> entries() {
        List<Entry> found = new ArrayList<>();
        if (tree != null) {
            DocumentFile root = DocumentFile.fromTreeUri(context, tree);
            if (root != null && root.isDirectory()) {
                walk(root, "", found);
            }
        }
        Collections.sort(found, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int byFolder = a.folder.toLowerCase(Locale.US).compareTo(b.folder.toLowerCase(Locale.US));
                return byFolder != 0 ? byFolder
                        : a.name.toLowerCase(Locale.US).compareTo(b.name.toLowerCase(Locale.US));
            }
        });
        if (videoUrl != null) {
            found.add(linkEntry(videoUrl));
        }
        return found;
    }

    private void walk(DocumentFile dir, String relative, List<Entry> into) {
        DocumentFile[] children = dir.listFiles();
        for (DocumentFile child : children) {
            String name = child.getName();
            if (name == null || name.startsWith(".")) {
                continue;       // hidden files and folders, as the Python server does
            }
            if (child.isDirectory()) {
                walk(child, relative.isEmpty() ? name : relative + "/" + name, into);
            } else if (isVideo(name)) {
                Uri uri = child.getUri();
                into.add(new Entry(
                        name,
                        relative,
                        relative.isEmpty() ? name : relative + "/" + name,
                        child.length(),
                        "/stream/" + encode(uri.toString()),
                        uri));
            }
        }
    }

    /** The entry for the optional web link. The TV fetches this itself; the phone is not involved. */
    static Entry linkEntry(String url) {
        Uri parsed = Uri.parse(url);
        String last = parsed.getLastPathSegment();
        String name = last == null || last.isEmpty() ? parsed.getHost() : last;
        if (name == null || name.isEmpty()) {
            name = url;
        }
        // The size is left at 0: finding it would mean a request to that server every time the
        // library is listed, which would be slow and would fail whenever the host is down.
        return new Entry(name, "Link", url, 0L, url, null);
    }

    private static boolean isVideo(String name) {
        String lower = name.toLowerCase(Locale.US);
        for (String suffix : VIDEO_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** The /api/movies body, in the same shape the Python server produces. */
    String toJson(List<Entry> entries) {
        JSONArray movies = new JSONArray();
        for (Entry entry : entries) {
            JSONObject item = new JSONObject();
            try {
                item.put("name", entry.name);
                item.put("folder", entry.folder);
                item.put("path", entry.path);
                item.put("size", entry.size);
                item.put("url", entry.url);
            } catch (org.json.JSONException e) {
                continue;   // a single bad entry should not lose the whole library
            }
            movies.put(item);
        }
        JSONObject root = new JSONObject();
        try {
            root.put("version", VERSION);
            root.put("root", rootName());
            root.put("count", entries.size());
            root.put("movies", movies);
            // toString(int) is declared to throw in the desktop org.json used by the unit tests,
            // though not in Android's, so it belongs inside this guard either way.
            return root.toString(2);
        } catch (org.json.JSONException e) {
            return "{\"version\":\"" + VERSION + "\",\"count\":0,\"movies\":[]}";
        }
    }

    private String rootName() {
        if (tree == null) {
            return "Link";
        }
        DocumentFile root = DocumentFile.fromTreeUri(context, tree);
        String name = root == null ? null : root.getName();
        return name == null ? "Movies" : name;
    }

    static String encode(String value) {
        try {
            // URLEncoder is form encoding, which turns a space into "+"; a URL path wants "%20".
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return value;   // UTF-8 is always present
        }
    }
}
