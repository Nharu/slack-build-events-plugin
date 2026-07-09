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
    public void firstMatchingRuleShadowsLaterMatchingRule() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("myjob");

        // Phase 1: broad rule first (SUCCESS off) shadows the narrow rule that would fire SUCCESS.
        NotificationRule broad1 = SlackTestHelpers.rule("my.*", List.of("failure"));
        broad1.setChannel("#broad");
        NotificationRule narrow1 = SlackTestHelpers.rule("myjob", List.of("success"));
        narrow1.setChannel("#narrow");
        SlackTestHelpers.config().setRules(List.of(broad1, narrow1));
        j.buildAndAssertSuccess(p);
        SlackTestHelpers.awaitDispatch();
        assertEquals("broad rule (SUCCESS off) shadows narrow rule; later rules not consulted", 0, sender.calls.get());

        // Phase 2 (positive control): narrow rule first fires SUCCESS; broad does not fire.
        NotificationRule narrow2 = SlackTestHelpers.rule("myjob", List.of("success"));
        narrow2.setChannel("#narrow");
        NotificationRule broad2 = SlackTestHelpers.rule("my.*", List.of("failure"));
        broad2.setChannel("#broad");
        SlackTestHelpers.config().setRules(List.of(narrow2, broad2));
        j.buildAndAssertSuccess(p);
        SlackTestHelpers.awaitDispatch();
        assertEquals("narrow rule first fires SUCCESS", 1, sender.calls.get());
        assertThat(sender.bodies.get(0), containsString("Build Succeeded"));
        assertThat(sender.bodies.get(0), containsString("\"channel\":\"#narrow\""));
        assertThat(sender.bodies.get(0), not(containsString("#broad")));
    }

    @Test
    public void zeroMatchingRulesFireNothing() throws Exception {
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("other.*", List.of("start", "success"))));

        j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
        SlackTestHelpers.awaitDispatch();

        assertEquals(0, sender.calls.get());
    }

    @Test
    public void invalidRegexFailsClosedAndIterationContinues() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("myjob");

        // Phase A: an invalid regex matches nothing (fires 0).
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("(unclosed", List.of("success"))));
        j.buildAndAssertSuccess(p);
        SlackTestHelpers.awaitDispatch();
        assertEquals("invalid regex must match nothing (fires 0)", 0, sender.calls.get());

        // Phase B (positive control): invalid rule first; iteration continues to the valid rule (proves no throw).
        NotificationRule bad = SlackTestHelpers.rule("(unclosed", List.of("success"));
        bad.setChannel("#bad");
        NotificationRule good = SlackTestHelpers.rule("myjob", List.of("success"));
        good.setChannel("#good");
        SlackTestHelpers.config().setRules(List.of(bad, good));
        j.buildAndAssertSuccess(p);
        SlackTestHelpers.awaitDispatch();
        assertEquals("iteration continues past invalid rule to valid rule (no throw)", 1, sender.calls.get());
        assertThat(sender.bodies.get(0), containsString("Build Succeeded"));
        assertThat(sender.bodies.get(0), containsString("\"channel\":\"#good\""));
        assertThat(sender.bodies.get(0), not(containsString("#bad")));
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
