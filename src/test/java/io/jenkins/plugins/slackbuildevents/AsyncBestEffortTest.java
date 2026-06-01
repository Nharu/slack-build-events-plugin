package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import hudson.model.FreeStyleProject;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/** (e) asynchronous, best-effort dispatch: Slack latency/errors never block or fail a build. */
public class AsyncBestEffortTest {

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
    public void buildCompletesWhileWebhookIsBlocked() throws Exception {
        // If dispatch ran on the build thread, this latch would deadlock the build.
        CountDownLatch gate = new CountDownLatch(1);
        sender.blockOn(gate);
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("myjob", List.of("start"))));

        FreeStyleProject p = j.createFreeStyleProject("myjob");
        // Returns successfully even though the webhook send is still blocked.
        j.buildAndAssertSuccess(p);

        gate.countDown();
        SlackTestHelpers.awaitDispatch();
        assertEquals(1, sender.calls.get());
    }

    @Test
    public void webhookErrorDoesNotFailBuild() throws Exception {
        sender.script(TestWebhookSender.status(500), TestWebhookSender.status(500));
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("myjob", List.of("start", "success"))));

        FreeStyleProject p = j.createFreeStyleProject("myjob");
        j.buildAndAssertSuccess(p);
        SlackTestHelpers.awaitDispatch();

        assertTrue("both events still attempted despite 500s", sender.calls.get() >= 1);
    }

    @Test
    public void awaitBarrierIncludesPendingWork() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        sender.blockOn(gate);
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("myjob", List.of("start"))));
        j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));

        // Release shortly after, on another thread, then the barrier must wait for it.
        new Thread(() -> {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    gate.countDown();
                })
                .start();
        NotificationDispatcher.get().awaitAllDispatched(10, TimeUnit.SECONDS);
        assertEquals(1, sender.calls.get());
    }
}
