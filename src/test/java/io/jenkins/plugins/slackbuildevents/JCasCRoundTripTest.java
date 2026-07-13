package io.jenkins.plugins.slackbuildevents;

import static io.jenkins.plugins.casc.misc.Util.getUnclassifiedRoot;
import static io.jenkins.plugins.casc.misc.Util.toStringFromYamlFile;
import static io.jenkins.plugins.casc.misc.Util.toYamlString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.model.CNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

/**
 * (g) JCasC round-trip against a fully-populated fixture shared by all three methods, locking both
 * directions: import (yaml-&gt;object) populates every global field and both ordered rules, export
 * (object-&gt;yaml) reproduces the whole slackTemplatedNotifier subtree byte-for-byte, and the raw
 * webhook secret is never serialized (only its encrypted token).
 */
public class JCasCRoundTripTest {

    @Rule
    public JenkinsConfiguredWithCodeRule j = new JenkinsConfiguredWithCodeRule();

    @Test
    @ConfiguredWithCode("casc-roundtrip.yml")
    public void importPopulatesConfig() {
        SlackNotifierGlobalConfig config = SlackNotifierGlobalConfig.get();
        assertNotNull(config);

        assertEquals("#builds", config.getDefaultChannel());
        assertEquals("#36a64f", config.getDefaultColor());
        assertEquals("slack-default-cred", config.getDefaultWebhookCredentialId());
        assertEquals(4, config.getMaxRetriesOn429());
        assertTrue(config.isHttpsOnly());
        // Stored verbatim — case is preserved (normalization happens only at compare time).
        assertEquals(List.of("HOOKS.slack.com", "mattermost.internal"), config.getWebhookHostAllowlist());
        assertEquals("global-start-tmpl", config.getDefaultStartTemplate());
        assertEquals("global-success-tmpl", config.getDefaultSuccessTemplate());
        assertEquals("global-failure-tmpl", config.getDefaultFailureTemplate());
        assertEquals("global-unstable-tmpl", config.getDefaultUnstableTemplate());
        assertEquals("global-aborted-tmpl", config.getDefaultAbortedTemplate());
        assertEquals("global-notbuilt-tmpl", config.getDefaultNotBuiltTemplate());

        assertEquals(2, config.getRules().size());

        // Rule order is insertion order (zeta first), not alphabetical — first-match-wins depends on it.
        NotificationRule zeta = config.getRules().get(0);
        assertEquals("zeta/deploy-.*", zeta.getJobNamePattern());
        assertEquals("#zeta-alerts", zeta.getChannel());
        assertEquals("slack-zeta-cred", zeta.getWebhookCredentialId());
        assertTrue(zeta.isNotifyStart());
        assertTrue(zeta.isNotifySuccess());
        assertTrue(zeta.isNotifyFailure());
        assertTrue(zeta.isNotifyUnstable());
        assertTrue(zeta.isNotifyAborted());
        assertTrue(zeta.isNotifyNotBuilt());
        assertEquals("z-start", zeta.getStartTemplate());
        assertEquals("z-success", zeta.getSuccessTemplate());
        assertEquals("z-failure", zeta.getFailureTemplate());
        assertEquals("z-unstable", zeta.getUnstableTemplate());
        assertEquals("z-aborted", zeta.getAbortedTemplate());
        assertEquals("z-notbuilt", zeta.getNotBuiltTemplate());

        NotificationRule alpha = config.getRules().get(1);
        assertEquals("alpha/build-.*", alpha.getJobNamePattern());
        assertEquals("#alpha-ci", alpha.getChannel());
        assertEquals("slack-alpha-cred", alpha.getWebhookCredentialId());
        assertFalse(alpha.isNotifyStart());
        assertTrue(alpha.isNotifySuccess());
        assertTrue(alpha.isNotifyFailure());
        assertFalse(alpha.isNotifyUnstable());
        assertTrue(alpha.isNotifyAborted());
        assertFalse(alpha.isNotifyNotBuilt());
        assertEquals("a-start", alpha.getStartTemplate());
        assertEquals("a-success", alpha.getSuccessTemplate());
        assertEquals("a-failure", alpha.getFailureTemplate());
        assertEquals("a-unstable", alpha.getUnstableTemplate());
        assertEquals("a-aborted", alpha.getAbortedTemplate());
        assertEquals("a-notbuilt", alpha.getNotBuiltTemplate());
    }

    @Test
    @ConfiguredWithCode("casc-roundtrip.yml")
    public void exportMatchesExpectedStructurally() throws Exception {
        // Serialize the slackTemplatedNotifier subtree and compare it byte-for-byte against a captured
        // expected YAML. Whole-subtree string equality locks every field, per-rule override, and the
        // rule list order at once — unlike a substring check, a single renamed, dropped, or reordered
        // field breaks equality.
        //
        // To regenerate casc-roundtrip-expected.yml after an intended change: run this export
        // (toYamlString(node)) and paste its output verbatim into that resource file (no comments or
        // extra bytes — it is read as raw bytes, not parsed). A JCasC/snakeyaml upgrade that changes
        // key ordering or quoting style is a fixture-refresh matter, not a product bug.
        ConfigurationContext context = new ConfigurationContext(ConfiguratorRegistry.get());
        CNode node = getUnclassifiedRoot(context).get("slackTemplatedNotifier");
        String exported = toYamlString(node);
        String expected = toStringFromYamlFile(this, "casc-roundtrip-expected.yml");
        assertThat(
                "exported YAML drifted from casc-roundtrip-expected.yml — if this change is intended "
                        + "(a product change to the serialized fields, or a JCasC/snakeyaml upgrade), regenerate "
                        + "the fixture per the regeneration note in this test; otherwise it may be a product regression",
                exported,
                is(expected));
    }

    @Test
    @ConfiguredWithCode("casc-roundtrip.yml")
    public void exportDoesNotLeakWebhookSecret() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ConfigurationAsCode.get().export(out);
        String exported = out.toString(StandardCharsets.UTF_8);

        // The raw webhook secret must never appear in exported YAML...
        assertThat(exported, not(containsString("https://hooks.slack.com/SUPER/SECRET")));
        // ...but it must still be exported in encrypted form. A purely negative assertion would pass
        // vacuously if the secret were dropped from the export entirely, so also assert the encrypted
        // token ({AQAA...} prefix) is present.
        assertThat(exported, containsString("{AQAA"));
    }
}
