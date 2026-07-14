package io.jenkins.plugins.slackbuildevents;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Result of {@link NotificationDispatcher#render}: a category plus the body to send. The category
 * always distinguishes send ({@code CLEAN}/{@code DEGRADED}/{@code FALLBACK}) from skip
 * ({@code ABORTED}), and {@link #event()} carries the failure-signal payload for the two failure
 * categories.
 *
 * <p>Expansion is not all-or-nothing: every resolvable token is always resolved, and only the tokens
 * that failed survive as literals. A template consisting of a single unresolved token can therefore
 * produce a DEGRADED body that coincides with the template text — but no resolvable token is ever
 * dropped, and every DEGRADED/FALLBACK render emits a WARNING.
 */
final class RenderOutcome {

    @NonNull
    private final RenderCategory category;

    /** Body to send; {@code null} only for {@code ABORTED} (nothing is sent). */
    @CheckForNull
    private final String text;

    /** Failure-signal payload; non-null only for {@code DEGRADED} and {@code FALLBACK}. */
    @CheckForNull
    private final RenderFailureEvent event;

    private RenderOutcome(
            @NonNull RenderCategory category, @CheckForNull String text, @CheckForNull RenderFailureEvent event) {
        this.category = category;
        this.text = text;
        this.event = event;
    }

    @NonNull
    static RenderOutcome clean(@NonNull String text) {
        return new RenderOutcome(RenderCategory.CLEAN, text, null);
    }

    @NonNull
    static RenderOutcome degraded(@NonNull String text, @NonNull RenderFailureEvent event) {
        return new RenderOutcome(RenderCategory.DEGRADED, text, event);
    }

    @NonNull
    static RenderOutcome fallback(@NonNull String text, @NonNull RenderFailureEvent event) {
        return new RenderOutcome(RenderCategory.FALLBACK, text, event);
    }

    @NonNull
    static RenderOutcome aborted() {
        return new RenderOutcome(RenderCategory.ABORTED, null, null);
    }

    @NonNull
    RenderCategory category() {
        return category;
    }

    @CheckForNull
    String text() {
        return text;
    }

    @CheckForNull
    RenderFailureEvent event() {
        return event;
    }
}
