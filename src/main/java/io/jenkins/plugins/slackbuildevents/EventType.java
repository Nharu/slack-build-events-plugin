package io.jenkins.plugins.slackbuildevents;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Result;

/**
 * The notification events this plugin can fire, each with its built-in Slack
 * attachment sidebar color.
 *
 * <p>Result-to-color mapping uses identity comparison because {@link Result}
 * constants are interned singletons.
 */
public enum EventType {

    START("#439FE0"),
    SUCCESS("good"),
    FAILURE("danger"),
    UNSTABLE("warning"),
    ABORTED("#808080"),
    NOT_BUILT("#808080");

    private final String defaultColor;

    EventType(String defaultColor) {
        this.defaultColor = defaultColor;
    }

    /** Built-in attachment color for this event when no override is configured. */
    @NonNull
    public String defaultColor() {
        return defaultColor;
    }

    /**
     * Maps a completed build {@link Result} to its completion event, or {@code null}
     * when the result does not correspond to a notifiable outcome.
     */
    @CheckForNull
    public static EventType fromResult(@CheckForNull Result result) {
        if (result == Result.SUCCESS) {
            return SUCCESS;
        }
        if (result == Result.FAILURE) {
            return FAILURE;
        }
        if (result == Result.UNSTABLE) {
            return UNSTABLE;
        }
        if (result == Result.ABORTED) {
            return ABORTED;
        }
        if (result == Result.NOT_BUILT) {
            return NOT_BUILT;
        }
        return null;
    }
}
