package io.jenkins.plugins.slackbuildevents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;

import hudson.model.FreeStyleProject;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/** (h) credential lookup: present → fire, absent/unknown → silent no-op, secret not logged. */
public class CredentialsLookupTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private TestWebhookSender sender;

    @Before
    public void setUp() {
        sender = new TestWebhookSender();
        SlackTestHelpers.installSeams(sender);
    }

    @Test
    public void noWebhookConfiguredFiresNothing() throws Exception {
        // No credential id anywhere → listener never dispatches.
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("myjob", List.of("start", "success"))));
        j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
        SlackTestHelpers.awaitDispatch();
        assertEquals(0, sender.calls.get());
    }

    @Test
    public void unknownCredentialIdSendsNothing() throws Exception {
        // Id set but no such credential → dispatcher looks up null and no-ops (no send).
        SlackTestHelpers.config().setDefaultWebhookCredentialId("ghost");
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("myjob", List.of("start"))));
        j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
        SlackTestHelpers.awaitDispatch();
        assertEquals(0, sender.calls.get());
    }

    @Test
    public void presentCredentialFires() throws Exception {
        SlackTestHelpers.addWebhookCredential("wh", "http://example.test/hook");
        SlackTestHelpers.config().setDefaultWebhookCredentialId("wh");
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("myjob", List.of("start"))));
        j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
        SlackTestHelpers.awaitDispatch();
        assertEquals(1, sender.calls.get());
    }

    @Test
    public void secretUrlNotWrittenToBuildLog() throws Exception {
        String secretUrl = "http://secret.example/UNIQUEWEBHOOKTOKEN";
        SlackTestHelpers.addWebhookCredential("wh", secretUrl);
        SlackTestHelpers.config().setDefaultWebhookCredentialId("wh");
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("myjob", List.of("start", "success"))));

        FreeStyleProject p = j.createFreeStyleProject("myjob");
        j.buildAndAssertSuccess(p);
        SlackTestHelpers.awaitDispatch();

        assertThat(JenkinsRule.getLog(p.getLastBuild()), not(containsString("UNIQUEWEBHOOKTOKEN")));
    }
}
