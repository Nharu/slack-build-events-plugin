package io.jenkins.plugins.slackbuildevents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;

import hudson.model.FreeStyleProject;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/** (b) allowlist gating (first-match, zero-match) and (d) channel resolution + ordering. */
public class RuleMatchingTest {

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
    public void firstMatchingRuleGoverns() throws Exception {
        NotificationRule first = SlackTestHelpers.rule("my.*", List.of("start"));
        first.setChannel("#first");
        NotificationRule second = SlackTestHelpers.rule("myjob", List.of("start"));
        second.setChannel("#second");
        SlackTestHelpers.config().setRules(List.of(first, second));

        j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
        SlackTestHelpers.awaitDispatch();

        assertEquals(1, sender.calls.get());
        assertThat(sender.bodies.get(0), containsString("\"channel\":\"#first\""));
        assertThat(sender.bodies.get(0), not(containsString("#second")));
    }

    @Test
    public void zeroMatchingRulesFireNothing() throws Exception {
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("other.*", List.of("start", "success"))));

        j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
        SlackTestHelpers.awaitDispatch();

        assertEquals(0, sender.calls.get());
    }

    @Test
    public void globalDefaultChannelUsedWhenRuleHasNone() throws Exception {
        SlackTestHelpers.config().setDefaultChannel("#global");
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("myjob", List.of("start"))));

        j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
        SlackTestHelpers.awaitDispatch();

        assertEquals(1, sender.calls.get());
        assertThat(sender.bodies.get(0), containsString("\"channel\":\"#global\""));
    }

    @Test
    public void patternIsFullMatchNotSubstring() throws Exception {
        // A prefix pattern must NOT match a longer name: 'myjo' != 'myjob' under full-match.
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("myjo", List.of("start", "success"))));
        j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
        SlackTestHelpers.awaitDispatch();
        assertEquals("'myjo' must not match 'myjob'", 0, sender.calls.get());
    }
}
