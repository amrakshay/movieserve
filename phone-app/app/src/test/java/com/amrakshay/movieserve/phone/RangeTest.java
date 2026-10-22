package com.amrakshay.movieserve.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The phone's range handling must behave exactly as the Python server's does, or the TV app would
 * seek correctly against a laptop and badly against a phone.
 *
 * <p>These cases mirror {@code server/tests/test_movieserve.py} one for one. If one side changes,
 * the other should too, and the test that fails is the point.
 */
public class RangeTest {

    private static final long TOTAL = 1000;

    @Test
    public void noHeaderMeansWholeFile() {
        assertFalse(Range.parse(null, TOTAL).isPartial());
        assertFalse(Range.parse("", TOTAL).isPartial());
        assertFalse(Range.parse(null, TOTAL).unsatisfiable);
    }

    @Test
    public void openEndedRange() {
        Range.Result result = Range.parse("bytes=200-", TOTAL);
        assertTrue(result.isPartial());
        assertEquals(200, result.range.start);
        assertEquals(999, result.range.end);
        assertEquals(800, result.range.length());
    }

    @Test
    public void closedRange() {
        Range.Result result = Range.parse("bytes=200-499", TOTAL);
        assertEquals(200, result.range.start);
        assertEquals(499, result.range.end);
        assertEquals(300, result.range.length());
    }

    @Test
    public void suffixRangeReturnsTheTail() {
        // How a player reads the index that MP4 keeps at the end of the file. Getting this wrong
        // can stop some videos playing at all, which looks like a broken file.
        Range.Result result = Range.parse("bytes=-300", TOTAL);
        assertEquals(700, result.range.start);
        assertEquals(999, result.range.end);
    }

    @Test
    public void suffixLongerThanTheFileIsClamped() {
        Range.Result result = Range.parse("bytes=-5000", TOTAL);
        assertEquals(0, result.range.start);
        assertEquals(999, result.range.end);
    }

    @Test
    public void endBeyondTheFileIsClamped() {
        Range.Result result = Range.parse("bytes=900-5000", TOTAL);
        assertEquals(900, result.range.start);
        assertEquals(999, result.range.end);
    }

    @Test
    public void startBeyondTheFileIsUnsatisfiable() {
        assertTrue(Range.parse("bytes=1000-", TOTAL).unsatisfiable);
        assertTrue(Range.parse("bytes=4000-5000", TOTAL).unsatisfiable);
    }

    @Test
    public void backwardsRangeIsUnsatisfiable() {
        assertTrue(Range.parse("bytes=500-200", TOTAL).unsatisfiable);
    }

    @Test
    public void zeroLengthSuffixIsUnsatisfiable() {
        assertTrue(Range.parse("bytes=-0", TOTAL).unsatisfiable);
    }

    @Test
    public void malformedHeaderFallsBackToTheWholeFile() {
        // Friendlier than refusing: the player still gets its video.
        assertFalse(Range.parse("bytes=abc", TOTAL).isPartial());
        assertFalse(Range.parse("seconds=1-2", TOTAL).isPartial());
        assertFalse(Range.parse("bytes=0-1,5-6", TOTAL).isPartial());
        assertFalse(Range.parse("bytes=abc", TOTAL).unsatisfiable);
    }

    @Test
    public void singleByte() {
        Range.Result result = Range.parse("bytes=0-0", TOTAL);
        assertEquals(0, result.range.start);
        assertEquals(0, result.range.end);
        assertEquals(1, result.range.length());
    }

    @Test
    public void whitespaceIsTolerated() {
        assertEquals(200, Range.parse("  bytes=200-499  ", TOTAL).range.start);
    }
}
