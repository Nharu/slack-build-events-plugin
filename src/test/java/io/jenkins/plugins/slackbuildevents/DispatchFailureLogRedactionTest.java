package io.jenkins.plugins.slackbuildevents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Integration coverage for the FINE catch-all in {@link NotificationDispatcher}: when a webhook URL
 * carrying a secret token fails inside the real {@link WebhookSender}, the emitted log record must
 * not contain the secret, yet must name the credential id for operator diagnostics.
 *
 * <p>The real {@code WebhookSender} is injected on purpose — the {@code TestWebhookSender} double
 * overrides {@code send} and bypasses the request-building path, so the containment code would
 * never run under it.
 */
public class DispatchFailureLogRedactionTest {

    private static final String SECRET = "SUPERSECRETTOKEN";
    /**
     * A null-host URL (note the single slash): {@code URI.create} accepts it, so it clears the
     * send-time {@link WebhookUrlPolicy} gate under the default config (no allowlist, https not
     * required) and actually reaches {@link WebhookSender#send}. There
     * {@code HttpRequest.newBuilder} rejects it with an {@code IllegalArgumentException} whose
     * message is {@code "unsupported URI " + uri} — i.e. it embeds the whole secret path. A URL
     * malformed enough to break {@code URI.create} instead would be rejected by the policy gate
     * before send, never exercising the containment.
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
        Logger logger = Logger.getLogger(NotificationDispatcher.class.getName());
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        handler.setLevel(Level.ALL);

        Level previousLevel = logger.getLevel();
        logger.addHandler(handler);
        // The logger's effective level defaults to INFO, so FINE records would be dropped before
        // reaching the handler; lower it to ALL for the duration of the test.
        logger.setLevel(Level.ALL);
        try {
            j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
            SlackTestHelpers.awaitDispatch();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }

        SimpleFormatter formatter = new SimpleFormatter();
        boolean sawFailureRecord = false;
        for (LogRecord record : records) {
            String rendered = formatter.format(record);
            // Negative assertion: the secret must never appear in any emitted record.
            assertThat(rendered, not(containsString(SECRET)));
            if (rendered.contains("Slack notification attempt failed")) {
                sawFailureRecord = true;
                // Positive assertion (guards against a vacuous green): the credential id is logged.
                assertThat(rendered, containsString(CREDENTIAL_ID));
            }
        }
        assertTrue("expected at least one dispatch-failure log record", sawFailureRecord);
    }
}
