package io.jenkins.plugins.slackbuildevents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.junit.Assert.assertEquals;

import hudson.util.FormValidation;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * config-time credential doCheck (global + per-rule). Warn-only, driven by the <b>stored</b>
 * allowlist/https-only policy, and it must never surface the secret webhook URL.
 */
public class SsrfDoCheckTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Before
    public void setUp() throws Exception {
        SlackTestHelpers.addWebhookCredential("wh", "https://hooks.slack.com/services/T000/B000/SUPERSECRETTOKEN");
    }

    @Test
    public void globalDoCheckWarnsWhenHostOutsideStoredAllowlist() {
        SlackNotifierGlobalConfig config = SlackTestHelpers.config();
        config.setWebhookHostAllowlist(List.of("internal.example"));

        FormValidation r = config.doCheckDefaultWebhookCredentialId("wh");

        assertEquals(FormValidation.Kind.WARNING, r.kind);
        assertThat(r.getMessage(), not(containsStringIgnoringCase("SUPERSECRETTOKEN")));
        assertThat(r.getMessage(), not(containsStringIgnoringCase("services")));
    }

    @Test
    public void globalDoCheckOkWhenHostAllowedByStoredAllowlist() {
        SlackNotifierGlobalConfig config = SlackTestHelpers.config();
        config.setWebhookHostAllowlist(List.of("slack.com"));

        assertEquals(FormValidation.Kind.OK, config.doCheckDefaultWebhookCredentialId("wh").kind);
    }

    @Test
    public void ruleDoCheckWarnsWhenHostOutsideStoredAllowlist() {
        SlackTestHelpers.config().setWebhookHostAllowlist(List.of("internal.example"));
        NotificationRule.DescriptorImpl descriptor =
                j.jenkins.getDescriptorByType(NotificationRule.DescriptorImpl.class);

        FormValidation r = descriptor.doCheckWebhookCredentialId("wh");

        assertEquals(FormValidation.Kind.WARNING, r.kind);
        assertThat(r.getMessage(), not(containsStringIgnoringCase("SUPERSECRETTOKEN")));
    }

    @Test
    public void doCheckOkWhenNoPolicyConfigured() {
        // Opt-in default: empty allowlist + https-only false → nothing is flagged.
        assertEquals(FormValidation.Kind.OK, SlackTestHelpers.config().doCheckDefaultWebhookCredentialId("wh").kind);
    }
}
