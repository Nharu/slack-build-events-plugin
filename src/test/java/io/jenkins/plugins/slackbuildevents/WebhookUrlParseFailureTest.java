package io.jenkins.plugins.slackbuildevents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.Test;

/**
 * Proves the source-containment invariant of {@link WebhookSender#send}: a malformed webhook URL
 * never leaks into the thrown exception. {@code URI.create} fails before the {@code HttpClient} is
 * ever touched, so this is a deterministic pure unit test needing no stub client or JenkinsRule.
 */
public class WebhookUrlParseFailureTest {

    // A secret token embedded in a URL made malformed by an illegal character (the trailing space).
    private static final String SECRET = "SUPERSECRETTOKEN";
    private static final String MALFORMED_SECRET_URL =
            "https://hooks.slack.com/services/T000/B000/" + SECRET + " x";

    @Test
    public void malformedUrlIsSanitizedIntoAGenericIOException() {
        IOException thrown =
                assertThrows(IOException.class, () -> new WebhookSender().send(MALFORMED_SECRET_URL, "{}"));

        // The message must be the static sanitized text and must not carry the secret.
        assertThat(thrown.getMessage(), containsString("Webhook URL is malformed"));
        assertThat(thrown.getMessage(), not(containsString(SECRET)));

        // No cause: a chained IAE would resurrect the secret through its toString().
        assertThat(thrown.getCause(), nullValue());

        // The full stack-trace text (including any cause chain) must be secret-free.
        StringWriter sw = new StringWriter();
        thrown.printStackTrace(new PrintWriter(sw));
        assertThat(sw.toString(), not(containsString(SECRET)));
    }
}
