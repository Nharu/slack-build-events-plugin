package io.jenkins.plugins.slackbuildevents;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/**
 * Builds the Slack Incoming Webhook JSON payload (legacy attachment form).
 *
 * <p>The legacy attachment form is used because the {@code color} sidebar (the
 * per-result color) is a core feature. Serialization via {@code net.sf.json}
 * (bundled in Jenkins core) escapes user-controlled content, preventing JSON
 * breakage or macro injection.
 */
final class SlackMessage {

    private SlackMessage() {}

    /**
     * @param channel best-effort hint for legacy custom-integration webhooks; ignored
     *                by modern single-channel webhooks. Omitted when blank.
     */
    @NonNull
    static String build(@CheckForNull String channel, @NonNull String color, @NonNull String text) {
        JSONArray mrkdwnIn = new JSONArray();
        mrkdwnIn.add("text");

        JSONObject attachment = new JSONObject();
        attachment.put("color", color);
        attachment.put("mrkdwn_in", mrkdwnIn);
        attachment.put("text", text);

        JSONArray attachments = new JSONArray();
        attachments.add(attachment);

        JSONObject payload = new JSONObject();
        if (channel != null && !channel.isEmpty()) {
            payload.put("channel", channel);
        }
        payload.put("attachments", attachments);
        return payload.toString();
    }
}
