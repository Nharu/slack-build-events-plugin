package io.jenkins.plugins.slackbuildevents;

/**
 * Classifies a dispatch-path failure for the rate-limited WARNING signal. This is the
 * <em>signal-point</em> category and is a distinct type from {@link RenderCategory}: it labels
 * where in the pipeline the failure arose (credential lookup, render, HTTP, transport, queue), and
 * its name is the leading component of every suppression signature key.
 */
enum FailureCategory {
    CREDENTIAL_MISSING,
    RENDER_DEGRADED,
    RENDER_FALLBACK,
    RENDER_ABORTED,
    HTTP_CLIENT_ERROR,
    HTTP_SERVER_ERROR,
    TRANSPORT_ERROR,
    UNEXPECTED_ERROR,
    QUEUE_SATURATED
}
