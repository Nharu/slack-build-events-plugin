package io.jenkins.plugins.slackbuildevents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;

import hudson.model.FreeStyleProject;
import hudson.model.Result;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;

/** (a) start/complete firing + payload, (c) per-result toggles. */
public class SlackNotifyRunListenerTest {

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
    public void startAndSuccessBothFire() throws Exception {
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("myjob", List.of("start", "success"))));

        FreeStyleProject p = j.createFreeStyleProject("myjob");
        j.buildAndAssertSuccess(p);
        SlackTestHelpers.awaitDispatch();

        assertEquals(2, sender.calls.get());
        String all = String.join("\n", sender.bodies);
        assertThat(all, containsString("Build Started"));
        assertThat(all, containsString("Build Succeeded"));
        assertThat(all, containsString("\"color\":\"good\""));
        assertThat(all, containsString("\"color\":\"#439FE0\""));
    }

    @Test
    public void onlyEnabledResultFires() throws Exception {
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("job.*", List.of("failure"))));

        FreeStyleProject good = j.createFreeStyleProject("jobGood");
        j.buildAndAssertSuccess(good);
        SlackTestHelpers.awaitDispatch();
        assertEquals("a success build must not fire under a failure-only rule", 0, sender.calls.get());

        FreeStyleProject bad = j.createFreeStyleProject("jobBad");
        bad.getBuildersList().add(new FailureBuilder());
        j.buildAndAssertStatus(Result.FAILURE, bad);
        SlackTestHelpers.awaitDispatch();

        assertEquals(1, sender.calls.get());
        assertThat(sender.bodies.get(0), containsString("Build Failed"));
        assertThat(sender.bodies.get(0), containsString("\"color\":\"danger\""));
    }
}
