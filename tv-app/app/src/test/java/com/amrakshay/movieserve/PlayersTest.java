package com.amrakshay.movieserve;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Which packages count as a real video player.
 *
 * <p>The listing itself needs a device, but the decision about what to keep does not, and that is
 * the part that can quietly go wrong: wrongly dropping a player makes it invisible, and wrongly
 * keeping a stub offers a choice that always fails.
 */
public class PlayersTest {

    @Test
    public void realPlayersAreKept() {
        assertTrue(Players.keep("org.videolan.vlc"));
        assertTrue(Players.keep("com.mxtech.videoplayer.ad"));
        assertTrue(Players.keep("com.mxtech.videoplayer.pro"));
        assertTrue(Players.keep("com.brouken.player"));          // Just Player
        assertTrue(Players.keep("com.mitv.videoplayer"));        // Xiaomi's built-in player
        assertTrue(Players.keep("com.google.android.videos"));
    }

    @Test
    public void androidsPlaceholderIsDropped() {
        // It claims the intent and then tells the user nothing can handle it. Offering it would
        // look like a choice and always fail.
        assertFalse(Players.keep("com.google.android.tv.frameworkpackagestubs"));
    }

    @Test
    public void otherNonPlayersAreDropped() {
        assertFalse(Players.keep("com.android.fallback"));
    }

    @Test
    public void weDoNotOfferOurselves() {
        assertFalse(Players.keep("com.amrakshay.movieserve"));
    }

    @Test
    public void nothingIsNotAPlayer() {
        assertFalse(Players.keep(null));
        assertFalse(Players.keep(""));
    }

    @Test
    public void matchingIgnoresCase() {
        // Package names are lowercase in practice, but the filter should not be the thing that
        // depends on that.
        assertFalse(Players.keep("COM.GOOGLE.ANDROID.TV.FRAMEWORKPACKAGESTUBS"));
    }

    @Test
    public void aPackageMerelyContainingAnExcludedNameIsKept() {
        // Exclusion is by exact name, so a real player must not be dropped for resembling one.
        assertTrue(Players.keep("com.google.android.tv.frameworkpackagestubs.player"));
        assertTrue(Players.keep("org.example.com.android.fallback"));
    }

    // viewIntent() is not tested here: building an Intent needs Uri.parse, which a plain JVM test
    // does not have. It would need Robolectric or a device, and what it would prove is that the
    // framework works rather than that this code does.
}
