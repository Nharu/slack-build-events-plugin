package io.jenkins.plugins.slackbuildevents;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Formats a duration in milliseconds as a compact, language-neutral symbol string.
 *
 * <p>Renders at most two adjacent units drawn from {@code d/h/m/s/ms} using truncating
 * integer division — the top non-zero unit plus the unit immediately below it when that
 * value is non-zero (e.g. {@code 2h 5m}, {@code 820ms}, {@code 0s}). Days are the open
 * top unit (no month/year rollover, since those are calendar approximations); a zero
 * duration renders {@code 0s}; negatives are defensively clamped to zero.
 *
 * <p>The output is ASCII-only and never consults {@code Locale}, {@code Messages}, or
 * {@link hudson.Util}, so it is identical under any {@code Locale.getDefault()}. This is
 * what keeps {@code ${SLACK_DURATION}} stable when notifications are rendered off the web
 * request, on the async dispatch daemon, where the locale falls back to the server JVM's.
 */
final class DurationFormat {

    private DurationFormat() {}

    private static final long MS_PER_SEC = 1_000L;
    private static final long MS_PER_MIN = 60_000L;
    private static final long MS_PER_HOUR = 3_600_000L;
    private static final long MS_PER_DAY = 86_400_000L;

    @NonNull
    static String format(long millis) {
        if (millis < 0L) {
            millis = 0L; // defensive; the caller already clamps
        }
        long days = millis / MS_PER_DAY;
        long hours = (millis % MS_PER_DAY) / MS_PER_HOUR;
        long mins = (millis % MS_PER_HOUR) / MS_PER_MIN;
        long secs = (millis % MS_PER_MIN) / MS_PER_SEC;
        long ms = millis % MS_PER_SEC;
        long[] vals = {days, hours, mins, secs, ms};
        String[] toks = {"d", "h", "m", "s", "ms"};
        int i = 0;
        while (i < vals.length && vals[i] == 0L) {
            i++;
        }
        if (i == vals.length) {
            return "0s"; // millis == 0
        }
        StringBuilder sb = new StringBuilder().append(vals[i]).append(toks[i]);
        if (i + 1 < vals.length && vals[i + 1] != 0L) {
            sb.append(' ').append(vals[i + 1]).append(toks[i + 1]);
        }
        return sb.toString();
    }
}
