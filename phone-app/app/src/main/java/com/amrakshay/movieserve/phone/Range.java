package com.amrakshay.movieserve.phone;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interpreting a {@code Range} request header.
 *
 * <p>This is the single most important piece of the server. Without it a player cannot seek:
 * dragging the scrub bar either fails outright or restarts the file from the beginning. The
 * behaviour deliberately mirrors the Python server in {@code server/movieserve.py}, so the TV
 * app sees the same thing whichever one it is pointed at.
 *
 * <p>Only a single range is understood. Players ask for one, and {@code multipart/byteranges} is
 * not worth the complexity.
 */
final class Range {

    /** "bytes=0-", "bytes=500-999", "bytes=-500". */
    private static final Pattern PATTERN = Pattern.compile("^bytes=(\\d*)-(\\d*)$");

    /** First byte wanted, inclusive. */
    final long start;
    /** Last byte wanted, inclusive. */
    final long end;

    private Range(long start, long end) {
        this.start = start;
        this.end = end;
    }

    long length() {
        return end - start + 1;
    }

    /** What the header asked for, when it can be satisfied. */
    static final class Result {
        /** The whole file: no header, or one too malformed to act on. */
        static final Result WHOLE = new Result(null, false);
        /** The request cannot be met; the caller answers 416 with the real length. */
        static final Result UNSATISFIABLE = new Result(null, true);

        final Range range;              // null unless this is a partial request
        final boolean unsatisfiable;

        private Result(Range range, boolean unsatisfiable) {
            this.range = range;
            this.unsatisfiable = unsatisfiable;
        }

        static Result of(long start, long end) {
            return new Result(new Range(start, end), false);
        }

        boolean isPartial() {
            return range != null;
        }
    }

    /**
     * Read a Range header against a file of {@code total} bytes.
     *
     * <p>A malformed header is ignored rather than refused: the player still gets its video, which
     * is friendlier than failing over a header it may not have meant.
     */
    static Result parse(String header, long total) {
        if (header == null || header.trim().isEmpty()) {
            return Result.WHOLE;
        }
        Matcher match = PATTERN.matcher(header.trim());
        if (!match.matches()) {
            return Result.WHOLE;
        }
        String first = match.group(1);
        String last = match.group(2);

        if (first.isEmpty() && last.isEmpty()) {
            return Result.WHOLE;
        }
        if (first.isEmpty()) {
            // "bytes=-500": the final 500 bytes. Players use this to read the index that MP4 and
            // friends keep at the end of the file, so getting it wrong can stop playback entirely.
            long wanted = parseLong(last);
            if (wanted <= 0) {
                return Result.UNSATISFIABLE;
            }
            return Result.of(Math.max(0, total - wanted), total - 1);
        }

        long start = parseLong(first);
        if (start < 0 || start >= total) {
            return Result.UNSATISFIABLE;
        }
        long end = last.isEmpty() ? total - 1 : parseLong(last);
        if (end < 0) {
            return Result.UNSATISFIABLE;
        }
        end = Math.min(end, total - 1);
        if (end < start) {
            return Result.UNSATISFIABLE;
        }
        return Result.of(start, end);
    }

    /** Digits only by the time we get here, but a huge value would still overflow. */
    private static long parseLong(String digits) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
