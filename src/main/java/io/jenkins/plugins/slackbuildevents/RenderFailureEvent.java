package io.jenkins.plugins.slackbuildevents;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;

/**
 * Carrier the render point hands to {@link NotificationDispatcher}'s failure-signal seam: the
 * material needed to build a rate-limited WARNING (signature key + human-readable body) for a
 * {@code DEGRADED} or {@code FALLBACK} render. Immutable, package-private — a plain struct, not a
 * domain object.
 */
final class RenderFailureEvent {

    /** Cap on cause-chain traversal, shared by {@link #unwrap} and the dispatcher's interrupt-cause scan. */
    static final int MAX_CAUSE_CHAIN_DEPTH = 16;

    /** {@code DEGRADED} or {@code FALLBACK} — retains the render severity distinction. */
    @NonNull
    final RenderCategory category;

    /** Original caught throwable — full stack for the system log. */
    @NonNull
    final Throwable cause;

    /** {@link #unwrap}ped actual cause — drives the WARNING class name and signature. */
    @NonNull
    final Throwable rootCause;

    @NonNull
    final String jobFullName;

    /** Build number of the run, captured so the WARNING body reads one consistent provenance. */
    final int buildNumber;

    /** The event that triggered the notification; WARNING-body context only, never in the signature. */
    @NonNull
    final EventType event;

    @NonNull
    final String webhookCredentialId;

    /** Raw template — first input to the template-centric signature (hashed, never embedded whole). */
    @NonNull
    final String template;

    /** Best-effort name of the token that failed the strict pass; {@code null} when unparseable. */
    @CheckForNull
    final String failedMacroHint;

    RenderFailureEvent(
            @NonNull RenderCategory category,
            @NonNull Throwable cause,
            @NonNull Throwable rootCause,
            @NonNull String jobFullName,
            int buildNumber,
            @NonNull EventType event,
            @NonNull String webhookCredentialId,
            @NonNull String template,
            @CheckForNull String failedMacroHint) {
        this.category = category;
        this.cause = cause;
        this.rootCause = rootCause;
        this.jobFullName = jobFullName;
        this.buildNumber = buildNumber;
        this.event = event;
        this.webhookCredentialId = webhookCredentialId;
        this.template = template;
        this.failedMacroHint = failedMacroHint;
    }

    /**
     * Strips the token-macro {@link MacroEvaluationException} wrapper (which always carries a cause)
     * to recover the real cause, string-independently — so the signature does not collapse onto the
     * constant wrapper message {@code "Error processing tokens"}. Bounded against a pathological cause
     * cycle.
     */
    @NonNull
    static Throwable unwrap(@NonNull Throwable throwable) {
        Throwable current = throwable;
        int guard = 0;
        while (current instanceof MacroEvaluationException
                && current.getCause() != null
                && current.getCause() != current
                && guard++ < MAX_CAUSE_CHAIN_DEPTH) {
            current = current.getCause();
        }
        return current;
    }
}
