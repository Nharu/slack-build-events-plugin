package io.jenkins.plugins.slackbuildevents;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A dispatch-failure WARNING split into its one-line {@code headline} and an optional multi-line
 * {@code detail} (e.g. a stack trace). {@link FailureLogThrottle} appends the suppression-count suffix
 * to the {@code headline} — keeping the volume signal on line 1 — and places the {@code detail} below,
 * instead of tacking the suffix onto the tail of a multi-line message where it would be buried.
 */
final class FailureMessage {

    @NonNull
    final String headline;

    @CheckForNull
    final String detail;

    /** A single-line failure with no detail body. */
    FailureMessage(@NonNull String headline) {
        this(headline, null);
    }

    FailureMessage(@NonNull String headline, @CheckForNull String detail) {
        this.headline = headline;
        this.detail = detail;
    }
}
