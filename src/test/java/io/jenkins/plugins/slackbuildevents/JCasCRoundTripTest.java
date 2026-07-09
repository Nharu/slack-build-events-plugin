package io.jenkins.plugins.slackbuildevents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

/** (g) JCasC round-trip: import populates config, export omits the secret value. */
public class JCasCRoundTripTest {

    @Rule
    public JenkinsConfiguredWithCodeRule j = new JenkinsConfiguredWithCodeRule();

    @Test
    @ConfiguredWithCode("casc-roundtrip.yml")
    public void importPopulatesConfig() {
        SlackNotifierGlobalConfig config = SlackNotifierGlobalConfig.get();
        assertNotNull(config);
        assertEquals("#jenkins", config.getDefaultChannel());
        assertEquals("slack-webhook-url", config.getDefaultWebhookCredentialId());
        assertEquals(2, config.getMaxRetriesOn429());
        assertTrue(config.isHttpsOnly());
        // Stored verbatim — case is preserved (normalization happens only at compare time).
        assertEquals(List.of("HOOKS.slack.com", "mattermost.internal"), config.getWebhookHostAllowlist());
        assertEquals(1, config.getRules().size());

        NotificationRule rule = config.getRules().get(0);
        assertEquals("dev/server.*", rule.getJobNamePattern());
        assertTrue(rule.isNotifyStart());
        assertTrue(rule.isNotifySuccess());
        assertTrue(rule.isNotifyFailure());
    }

    @Test
    @ConfiguredWithCode("casc-roundtrip.yml")
    public void exportRoundTripsWithoutLeakingSecret() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ConfigurationAsCode.get().export(out);
        String exported = out.toString(StandardCharsets.UTF_8);

        assertThat(exported, containsString("slackTemplatedNotifier"));
        assertThat(exported, containsString("dev/server.*"));
        // Allowlist is exported verbatim, preserving the original case (export == import).
        assertThat(exported, containsString("HOOKS.slack.com"));
        // The raw webhook secret must never appear in exported YAML.
        assertThat(exported, not(containsString("https://hooks.slack.com/SUPER/SECRET")));
    }
}
