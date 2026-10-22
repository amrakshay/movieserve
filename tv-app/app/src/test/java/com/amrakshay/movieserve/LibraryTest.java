package com.amrakshay.movieserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Parsing the server's JSON and turning it into rows.
 *
 * <p>Plain JVM tests: no emulator, no device. This is the part of the app most likely to break
 * when the server changes, so it is the part worth pinning down.
 */
public class LibraryTest {

    private static final String BODY = "{"
            + "\"version\":\"1.0.0\",\"root\":\"Movies\",\"count\":3,\"movies\":["
            + "{\"name\":\"Top.mkv\",\"folder\":\"\",\"path\":\"Top.mkv\","
            + "\"size\":1610612736,\"url\":\"/stream/Top.mkv\"},"
            + "{\"name\":\"Chase.mp4\",\"folder\":\"Action\",\"path\":\"Action/Chase.mp4\","
            + "\"size\":524288000,\"url\":\"/stream/Action/Chase.mp4\"},"
            + "{\"name\":\"Duel.mkv\",\"folder\":\"Action\",\"path\":\"Action/Duel.mkv\","
            + "\"size\":0,\"url\":\"/stream/Action/Duel.mkv\"}"
            + "]}";

    @Test
    public void parsesEveryMovie() throws Exception {
        List<Library.Item> items = Library.parse(BODY);
        assertEquals(3, items.size());
        assertEquals("Top.mkv", items.get(0).name);
        assertEquals("Action", items.get(1).folder);
        assertEquals("/stream/Action/Chase.mp4", items.get(1).url);
        assertEquals(524288000L, items.get(1).size);
    }

    @Test
    public void emptyLibraryParsesToAnEmptyList() throws Exception {
        assertTrue(Library.parse("{\"movies\":[]}").isEmpty());
    }

    @Test
    public void missingOptionalFieldsFallBack() throws Exception {
        // Only path and url are required; the rest should degrade rather than throw.
        List<Library.Item> items = Library.parse(
                "{\"movies\":[{\"path\":\"A.mkv\",\"url\":\"/stream/A.mkv\"}]}");
        assertEquals("A.mkv", items.get(0).name);
        assertEquals("", items.get(0).folder);
        assertEquals(0L, items.get(0).size);
    }

    @Test
    public void rejectsSomethingThatIsNotAMovieserveResponse() {
        // Pointing the app at any other web server should be an error the user can act on,
        // not a crash.
        assertThrows(JSONException.class, () -> Library.parse("<html>hello</html>"));
        assertThrows(JSONException.class, () -> Library.parse("{\"something\":\"else\"}"));
    }

    @Test
    public void groupsRowsUnderAHeadingPerFolder() throws Exception {
        List<Library.Row> rows = Library.rows(Library.parse(BODY));
        // "Movies" heading, Top.mkv, "Action" heading, Chase.mp4, Duel.mkv
        assertEquals(5, rows.size());
        assertEquals(Library.Row.HEADER, rows.get(0).kind);
        assertEquals("Movies", rows.get(0).title);
        assertEquals(Library.Row.MOVIE, rows.get(1).kind);
        assertEquals(Library.Row.HEADER, rows.get(2).kind);
        assertEquals("Action", rows.get(2).title);
        assertEquals(Library.Row.MOVIE, rows.get(3).kind);
        assertEquals(Library.Row.MOVIE, rows.get(4).kind);
    }

    @Test
    public void emptyItemsProduceNoRows() {
        assertTrue(Library.rows(Arrays.asList()).isEmpty());
    }

    @Test
    public void readableSize() throws Exception {
        List<Library.Item> items = Library.parse(BODY);
        assertEquals("1.5 GB", items.get(0).readableSize());
        assertEquals("500 MB", items.get(1).readableSize());
        assertEquals("", items.get(2).readableSize());   // unknown size shows nothing
    }

    @Test
    public void localFilesGetTheServerAddressInFront() throws Exception {
        List<Library.Item> items = Library.parse(BODY);
        assertEquals("http://192.168.0.116:18080/stream/Top.mkv",
                items.get(0).playbackUrl("http://192.168.0.116:18080"));
    }

    @Test
    public void absoluteLinksArePassedThroughUntouched() throws Exception {
        // A --video-url entry points somewhere else entirely; prefixing it with the laptop's
        // address would produce "http://192.168.0.116:18080https://..." and fail to play.
        List<Library.Item> items = Library.parse(
                "{\"movies\":[{\"name\":\"Remote.mkv\",\"folder\":\"Link\","
                        + "\"path\":\"https://example.com/Remote.mkv\",\"size\":0,"
                        + "\"url\":\"https://example.com/Remote.mkv\"}]}");
        assertEquals("https://example.com/Remote.mkv",
                items.get(0).playbackUrl("http://192.168.0.116:18080"));
    }

    @Test
    public void plainHttpLinksArePassedThroughToo() throws Exception {
        List<Library.Item> items = Library.parse(
                "{\"movies\":[{\"path\":\"x\",\"url\":\"http://example.com/a.mp4\"}]}");
        assertEquals("http://example.com/a.mp4", items.get(0).playbackUrl("http://10.0.0.1:18080"));
    }

    @Test
    public void normaliseAddsSchemeAndTrimsTrailingSlash() {
        assertEquals("http://192.168.0.116:18080", Prefs.normalise("192.168.0.116:18080"));
        assertEquals("http://192.168.0.116:18080", Prefs.normalise(" http://192.168.0.116:18080/ "));
        assertEquals("http://192.168.0.116:18080", Prefs.normalise("http://192.168.0.116:18080///"));
        assertEquals("https://example.com", Prefs.normalise("https://example.com"));
        assertEquals("", Prefs.normalise(""));
        assertEquals("", Prefs.normalise(null));
    }
}
