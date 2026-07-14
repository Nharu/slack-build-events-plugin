package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit coverage for the suppression window: dedup within a window, window-roll piggyback of the
 * suppressed count, per-signature independence, the lazy sweep's summary flush, and the bounded
 * fail-open saturation notice. Pure (no Jenkins) and clock-injected, so it is fully deterministic.
 */
public class FailureLogThrottleTest {

    private static final long T0 = 1_000_000_000L;

    @Test
    public void suppressesRepeatsWithinWindow() {
        FailureLogThrottle throttle = new FailureLogThrottle();
        try (LogCapture logs = new LogCapture(FailureLogThrottle.class)) {
            throttle.recordFailure("sigA", T0, () -> new FailureMessage("sigA failure"));
            throttle.recordFailure("sigA", T0 + 1_000, () -> new FailureMessage("sigA failure"));
            throttle.recordFailure("sigA", T0 + 2_000, () -> new FailureMessage("sigA failure"));
            assertEquals(1, logs.warningsContaining("sigA failure"));
        }
    }

    @Test
    public void windowRollPiggybacksSuppressedCount() {
        FailureLogThrottle throttle = new FailureLogThrottle();
        try (LogCapture logs = new LogCapture(FailureLogThrottle.class)) {
            throttle.recordFailure("sigA", T0, () -> new FailureMessage("sigA failure"));
            throttle.recordFailure("sigA", T0 + 1_000, () -> new FailureMessage("sigA failure")); // suppressed
            throttle.recordFailure("sigA", T0 + 2_000, () -> new FailureMessage("sigA failure")); // suppressed
            // Window rolls: the next first log carries the previous window's suppressed count.
            throttle.recordFailure("sigA", T0 + FailureLogThrottle.WINDOW_MS, () -> new FailureMessage("sigA failure"));
            assertEquals(2, logs.warningCount());
            assertEquals(1, logs.warningsContaining("2 similar failure(s) suppressed"));
        }
    }

    @Test
    public void distinctSignaturesEachLogOnce() {
        FailureLogThrottle throttle = new FailureLogThrottle();
        try (LogCapture logs = new LogCapture(FailureLogThrottle.class)) {
            throttle.recordFailure("sigA", T0, () -> new FailureMessage("A"));
            throttle.recordFailure("sigB", T0, () -> new FailureMessage("B"));
            assertEquals(1, logs.warningsContaining("A"));
            assertEquals(1, logs.warningsContaining("B"));
        }
    }

    @Test
    public void expiredWindowsAreSweptWithSummaryFlush() {
        FailureLogThrottle throttle = new FailureLogThrottle();
        try (LogCapture logs = new LogCapture(FailureLogThrottle.class)) {
            // Fill to the cap; each signature has one suppressed repeat so its tail count is non-zero.
            for (int i = 0; i < FailureLogThrottle.MAX_TRACKED_SIGNATURES; i++) {
                String sig = "sig" + i;
                throttle.recordFailure(sig, T0, () -> new FailureMessage(sig + " failure"));
                throttle.recordFailure(sig, T0 + 1_000, () -> new FailureMessage(sig + " failure"));
            }
            // A new signature after the window elapsed forces a sweep of the (now expired) entries,
            // each of which flushes a one-line summary for its stranded suppressed count.
            long later = T0 + FailureLogThrottle.WINDOW_MS;
            throttle.recordFailure("overflow", later, () -> new FailureMessage("overflow failure"));

            assertEquals(
                    FailureLogThrottle.MAX_TRACKED_SIGNATURES, logs.warningsContaining("(summary flush)"));
            // The new signature was tracked and logged normally (not dropped as fail-open).
            assertEquals(1, logs.warningsContaining("overflow failure"));
            assertEquals(0, logs.warningsContaining("throttle saturated"));
        }
    }

    @Test
    public void failOpenEmitsBoundedSaturationNoticeWhenCannotReclaim() {
        FailureLogThrottle throttle = new FailureLogThrottle();
        try (LogCapture logs = new LogCapture(FailureLogThrottle.class)) {
            // Fill to the cap within a single window so nothing can be swept.
            for (int i = 0; i < FailureLogThrottle.MAX_TRACKED_SIGNATURES; i++) {
                String sig = "sig" + i;
                throttle.recordFailure(sig, T0, () -> new FailureMessage(sig + " failure"));
            }
            // Over-cap new signatures in the same window fail open: their detail is dropped and a single
            // coarse-rate-limited saturation notice is emitted (once per window).
            throttle.recordFailure("extra1", T0 + 1_000, () -> new FailureMessage("extra1 failure"));
            throttle.recordFailure("extra2", T0 + 2_000, () -> new FailureMessage("extra2 failure"));

            assertEquals(0, logs.warningsContaining("extra1 failure"));
            assertEquals(0, logs.warningsContaining("extra2 failure"));
            assertEquals(1, logs.warningsContaining("throttle saturated"));
        }
    }

    @Test
    public void resetClearsWindows() {
        FailureLogThrottle throttle = new FailureLogThrottle();
        try (LogCapture logs = new LogCapture(FailureLogThrottle.class)) {
            throttle.recordFailure("sigA", T0, () -> new FailureMessage("sigA failure"));
            throttle.reset();
            // After reset the same signature is a fresh first-log in the same window.
            throttle.recordFailure("sigA", T0 + 1_000, () -> new FailureMessage("sigA failure"));
            assertTrue(logs.warningsContaining("sigA failure") == 2);
        }
    }
}
