package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Integration coverage for the dispatch failure-signal path when a webhook URL carrying a secret token
 * fails inside the real {@link WebhookSender}: the emitted WARNING must not contain the secret, yet must
 * name the credential id for operator diagnostics.
 *
 * <p>The real {@code WebhookSender} is injected on purpose — the {@code TestWebhookSender} double
 * overrides {@code send} and bypasses the request-building path, so the source containment (which turns
 * a malformed URL into a generic {@code IOException}, never leaking the secret) would not run under it.
 * The failure surfaces as a rate-limited WARNING through {@link FailureLogThrottle}, not the old FINE
 * catch-log.
 */
public class DispatchFailureLogRedactionTest {

    private static final String SECRET = "SUPERSECRETTOKEN";
    /**
     * A null-host URL (note the single slash): {@code URI.create} accepts it, so it clears the send-time
     * {@link WebhookUrlPolicy} gate under the default config (no allowlist, https not required) and
     * actually reaches {@link WebhookSender#send}, where {@code HttpRequest.newBuilder} rejects it — the
     * {@code IllegalArgumentException} message would embed the whole secret path if it were not contained.
     */
    private static final String SECRET_BEARING_UNSUPPORTED_URL = "http:/services/T000/B000/" + SECRET;

    private static final String CREDENTIAL_ID = "leaky-wh";

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Before
    public void setUp() throws Exception {
        SlackTestHelpers.installSeams(new WebhookSender());
        SlackTestHelpers.addWebhookCredential(CREDENTIAL_ID, SECRET_BEARING_UNSUPPORTED_URL);
        SlackTestHelpers.config().setDefaultWebhookCredentialId(CREDENTIAL_ID);
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("myjob", List.of("start"))));
    }

    @Test
    public void malformedUrlFailureIsLoggedWithoutTheSecret() throws Exception {
        try (LogCapture logs = new LogCapture(FailureLogThrottle.class)) {
            j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
            SlackTestHelpers.awaitDispatch();

            // Negative: the secret must never appear in any WARNING.
            assertEquals(0, logs.warningsContaining(SECRET));
            // Positive (guards against a vacuous green): the failure surfaced as a WARNING that names the
            // credential id for operator diagnostics.
            assertEquals(1, logs.warningsContaining(CREDENTIAL_ID));
        }
    }
}
