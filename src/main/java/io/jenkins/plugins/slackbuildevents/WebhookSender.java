package io.jenkins.plugins.slackbuildevents;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Performs the blocking HTTP POST to a Slack Incoming Webhook. A single shared
 * {@link HttpClient} is used; the {@code send} call is meant to run on the bounded
 * dispatch pool, never on a build thread.
 *
 * <p>The {@link HttpClient} is injectable so tests can supply a stub.
 */
class WebhookSender {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient client;

    WebhookSender() {
        // Redirect guard: rely on the default followRedirects policy of NEVER. Do NOT add
        // .followRedirects(NORMAL/ALWAYS) — a 30x could redirect a send-time-validated host to
        // an internal one, bypassing the webhook host allowlist (SSRF hardening).
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    WebhookSender(@NonNull HttpClient client) {
        this.client = client;
    }

    /** Sends the payload and returns the HTTP status with any {@code Retry-After} header. */
    @NonNull
    Response send(@NonNull String url, @NonNull String jsonBody) throws IOException, InterruptedException {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            // The IAE message embeds the secret webhook URL; never propagate its message or cause.
            throw new IOException("Webhook URL is malformed"); // no cause, no URL
        }
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
        return new Response(response.statusCode(), retryAfter);
    }

    /** Minimal HTTP outcome the dispatcher needs (status + raw {@code Retry-After}). */
    static final class Response {
        private final int statusCode;
        @CheckForNull
        private final String retryAfter;

        Response(int statusCode, @CheckForNull String retryAfter) {
            this.statusCode = statusCode;
            this.retryAfter = retryAfter;
        }

        int statusCode() {
            return statusCode;
        }

        @CheckForNull
        String retryAfter() {
            return retryAfter;
        }
    }
}
