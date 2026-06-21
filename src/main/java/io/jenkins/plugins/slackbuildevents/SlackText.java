package io.jenkins.plugins.slackbuildevents;

import edu.umd.cs.findbugs.annotations.NonNull;

/** Slack mrkdwn text helpers. */
final class SlackText {

    private SlackText() {}

    /**
     * Escapes the three Slack mrkdwn control characters in a macro-computed value, so that
     * attacker-influenced content (e.g. a crafted git branch name) cannot inject
     * {@code <url|label>} link markup into a notification.
     *
     * <p>Per Slack's message-formatting guide the control set is exactly {@code &}, {@code <},
     * {@code >}, converted to HTML entities, and {@code &} must be replaced first.
     *
     * <p>This is applied only to computed macro values, never to admin-authored template text:
     * escaping a whole template would also neutralize the legitimate
     * {@code <${SLACK_BUILD_URL}|...>} links the default templates depend on.
     */
    @NonNull
    static String escape(@NonNull String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
