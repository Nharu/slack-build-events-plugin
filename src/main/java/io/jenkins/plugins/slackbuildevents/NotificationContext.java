package io.jenkins.plugins.slackbuildevents;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import hudson.model.TaskListener;

/**
 * Immutable capture of everything the async dispatcher needs to render and send
 * one notification. Captured on the build/event thread; consumed on a dispatch
 * worker thread.
 *
 * <p>{@code listener} is the live {@link TaskListener} for start events (log stream
 * open) and {@link TaskListener#NULL} for completion events (the build log stream is
 * closing during {@code Run.execute()} teardown, so writing to it risks a dead stream).
 */
final class NotificationContext {

    @NonNull
    private final Run<?, ?> run;
    @NonNull
    private final TaskListener listener;
    @CheckForNull
    private final String channel;
    @NonNull
    private final String webhookCredentialId;
    @NonNull
    private final String template;
    @NonNull
    private final String color;
    /** Start-time branch snapshot, captured on the event thread; {@code null} for completion events. */
    @CheckForNull
    private final String branchHint;

    NotificationContext(
            @NonNull Run<?, ?> run,
            @NonNull TaskListener listener,
            @CheckForNull String channel,
            @NonNull String webhookCredentialId,
            @NonNull String template,
            @NonNull String color,
            @CheckForNull String branchHint) {
        this.run = run;
        this.listener = listener;
        this.channel = channel;
        this.webhookCredentialId = webhookCredentialId;
        this.template = template;
        this.color = color;
        this.branchHint = branchHint;
    }

    @NonNull
    Run<?, ?> run() {
        return run;
    }

    @NonNull
    TaskListener listener() {
        return listener;
    }

    @CheckForNull
    String channel() {
        return channel;
    }

    @NonNull
    String webhookCredentialId() {
        return webhookCredentialId;
    }

    @NonNull
    String template() {
        return template;
    }

    @NonNull
    String color() {
        return color;
    }

    @CheckForNull
    String branchHint() {
        return branchHint;
    }
}
