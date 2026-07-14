package io.jenkins.plugins.slackbuildevents;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Volume-bounds the dispatch-failure WARNINGs so a persistently misconfigured webhook
 * (which fails on every build, possibly across hundreds of jobs) collapses to at most one
 * system-log line per signature per fixed 5-minute window instead of flooding the log.
 *
 * <p>State is keyed by a caller-supplied signature that deliberately excludes build/job
 * identifiers (see the dispatcher's signal map), so all builds sharing a root cause fold
 * onto one window. Time is injected as the {@code now} argument (the dispatcher's
 * {@code clock} seam) — this class never reads a wall clock, keeping tests deterministic.
 *
 * <p>All per-signature state transitions happen inside a {@link ConcurrentHashMap#compute}
 * bin lock; the actual {@link Logger} emission is done off-lock. Memory is approximately bounded by
 * {@link #MAX_TRACKED_SIGNATURES} — a soft limit: the size check and the insert are not one atomic step,
 * so a few concurrent dispatch-pool callers can overshoot it by up to the pool width before the next
 * sweep reclaims the excess. An over-cap insert first sweeps expired windows; if still saturated it
 * fails open, meaning over-cap <em>new</em> signatures are dropped (not logged individually) until
 * capacity frees up, while already-tracked signatures keep logging normally. That drop is itself
 * announced by a single coarse-rate-limited saturation notice per window, so it never becomes unbounded.
 */
final class FailureLogThrottle {

    private static final Logger LOGGER = Logger.getLogger(FailureLogThrottle.class.getName());

    /** Fixed suppression window (not an admin/JCasC setting). */
    static final long WINDOW_MS = TimeUnit.MINUTES.toMillis(5);

    /** Memory guard on distinct tracked signatures; consistent with the dispatcher's retry cap. */
    static final int MAX_TRACKED_SIGNATURES = 500;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /** Coarse rate-limit for the fail-open saturation notice; {@link Long#MIN_VALUE} = never fired. */
    private final AtomicLong saturationWindowStart = new AtomicLong(Long.MIN_VALUE);

    /** Mutable per-signature window: when it opened and how many logs it has since suppressed. */
    private static final class Window {
        private long start;
        private long suppressed;

        Window(long start) {
            this.start = start;
        }
    }

    /**
     * Records one failure under {@code signature}. Emits a WARNING (built lazily from
     * {@code messageSupplier}) only when this is the first occurrence in a fresh window;
     * otherwise counts it as suppressed. When a window rolls, the first log of the new
     * window piggybacks the previous window's suppressed count onto the headline so the
     * volume signal stays on line 1, above any multi-line detail.
     */
    void recordFailure(@NonNull String signature, long now, @NonNull Supplier<FailureMessage> messageSupplier) {
        if (!windows.containsKey(signature) && windows.size() >= MAX_TRACKED_SIGNATURES) {
            // At cap for a new signature: try to reclaim expired windows first.
            sweepExpired(now);
        }
        if (!windows.containsKey(signature) && windows.size() >= MAX_TRACKED_SIGNATURES) {
            // Still saturated → fail open: drop this line's detail but surface a single,
            // coarse-rate-limited saturation signal so the operator knows visibility degraded.
            maybeLogSaturation(now);
            return;
        }

        // -1 = suppress (no emit); >= 0 = emit, carrying the previous window's suppressed count.
        long[] piggyback = {-1L};
        windows.compute(signature, (key, window) -> {
            if (window == null) {
                piggyback[0] = 0L;
                return new Window(now);
            }
            if (now - window.start >= WINDOW_MS) {
                piggyback[0] = window.suppressed;
                window.start = now;
                window.suppressed = 0L;
                return window;
            }
            window.suppressed++;
            return window;
        });

        if (piggyback[0] >= 0L) {
            long suppressed = piggyback[0];
            FailureMessage message;
            try {
                message = messageSupplier.get();
            } catch (RuntimeException supplierFailure) {
                // The message builder threw — e.g. a throwable's getMessage()/stack trace, or a live model
                // dereference during teardown. This branch registered the window BEFORE building the
                // message, so letting it propagate would drop the failure with no log AND blackhole the
                // signature for the rest of the window. Emit a visible stand-in instead, keeping the window
                // intact, and never propagate out of recordFailure.
                message = new FailureMessage("Slack notification failure signal — its message could not be built ("
                        + supplierFailure.getClass().getName() + ")");
            }
            String headline = message.headline;
            if (suppressed > 0L) {
                headline = headline + " [" + suppressed + " similar failure(s) suppressed in the previous "
                        + (WINDOW_MS / 60_000L) + "-minute window]";
            }
            String full = message.detail != null ? headline + "\n" + message.detail : headline;
            LOGGER.log(Level.WARNING, full);
        }
    }

    /**
     * Removes windows whose 5-minute span has elapsed. A reclaimed window that still holds
     * suppressed occurrences emits a one-line summary flush, so a signature that bursts then
     * goes silent does not strand its tail count.
     */
    private void sweepExpired(long now) {
        for (String key : windows.keySet()) {
            long[] flushed = {0L};
            windows.computeIfPresent(key, (k, window) -> {
                if (now - window.start >= WINDOW_MS) {
                    flushed[0] = window.suppressed;
                    return null;
                }
                return window;
            });
            if (flushed[0] > 0L) {
                LOGGER.log(
                        Level.WARNING,
                        "Slack notification failures suppressed: " + flushed[0]
                                + " occurrence(s) of signature '" + key + "' in the last window (summary flush)");
            }
        }
    }

    /** Emits the fail-open saturation notice at most once per window (overflow-safe first-fire). */
    private void maybeLogSaturation(long now) {
        long current = saturationWindowStart.get();
        boolean elapsed = current == Long.MIN_VALUE || now - current >= WINDOW_MS;
        if (elapsed && saturationWindowStart.compareAndSet(current, now)) {
            LOGGER.log(
                    Level.WARNING,
                    "Slack notification failure-log throttle saturated at " + windows.size()
                            + " tracked signatures; new failure signatures are dropped until capacity frees up");
        }
    }

    /** Clears all tracked windows; used to isolate suppression state between tests. */
    void reset() {
        windows.clear();
        saturationWindowStart.set(Long.MIN_VALUE);
    }
}
