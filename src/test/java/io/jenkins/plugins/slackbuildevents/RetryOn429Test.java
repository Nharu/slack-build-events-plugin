package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/** (j) 429 handling: bounded non-blocking retry, then terminal best-effort drop. */
public class RetryOn429Test {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private TestWebhookSender sender;

    @Before
    public void setUp() throws Exception {
        sender = new TestWebhookSender();
        SlackTestHelpers.installSeams(sender);
        SlackTestHelpers.addWebhookCredential("wh", "http://example.test/hook");
        SlackTestHelpers.config().setDefaultWebhookCredentialId("wh");
    }

    @Test
    public void retriesOnceThenSucceeds() throws Exception {
        SlackTestHelpers.config().setMaxRetriesOn429(1);
        sender.script(TestWebhookSender.status429RetryNow(), TestWebhookSender.status(200));
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("myjob", List.of("start"))));

        j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
        SlackTestHelpers.awaitDispatch();

        assertEquals("initial attempt + one retry", 2, sender.calls.get());
    }

    @Test
    public void stopsAfterRetryBudgetExhausted() throws Exception {
        SlackTestHelpers.config().setMaxRetriesOn429(2);
        sender.script(
                TestWebhookSender.status429RetryNow(),
                TestWebhookSender.status429RetryNow(),
                TestWebhookSender.status429RetryNow(),
                TestWebhookSender.status429RetryNow());
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("myjob", List.of("start"))));

        j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
        SlackTestHelpers.awaitDispatch();

        // 1 initial + 2 retries = 3 attempts, then dropped (no further calls).
        assertEquals(3, sender.calls.get());
        // Retry-budget exhaustion is a normal terminal outcome, not a pool-unavailability drop.
        assertEquals(0, NotificationDispatcher.get().droppedCount());
    }
}
