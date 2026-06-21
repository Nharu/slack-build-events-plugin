package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Form-submit round-trip for the global config. Exercises the new {@code configure()} override
 * (bindJSON + a single save) that replaced the per-setter {@code save()} calls — a code path that
 * the JCasC and direct-setter round-trips bypass, so it needs its own coverage.
 */
public class GlobalConfigFormRoundTripTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void formSubmitPreservesAllFields() throws Exception {
        SlackNotifierGlobalConfig config = SlackNotifierGlobalConfig.get();
        assertNotNull(config);
        config.setDefaultChannel("#ops");
        config.setMaxRetriesOn429(3);
        config.setDefaultColor("#36a64f");
        config.setDefaultStartTemplate("start ${SLACK_BUILD_URL}");
        config.setDefaultSuccessTemplate("ok");
        config.setDefaultFailureTemplate("fail");
        config.setDefaultUnstableTemplate("unstable");
        config.setDefaultAbortedTemplate("aborted");
        config.setDefaultNotBuiltTemplate("notbuilt");
        config.setRules(List.of(SlackTestHelpers.rule("dev/.*", List.of("start", "success"))));

        // Submits the global-config form and reloads → drives configure() → bindJSON + save.
        j.configRoundtrip();

        SlackNotifierGlobalConfig after = SlackNotifierGlobalConfig.get();
        assertNotNull(after);
        assertEquals("#ops", after.getDefaultChannel());
        assertEquals(3, after.getMaxRetriesOn429());
        assertEquals("#36a64f", after.getDefaultColor());
        assertEquals("start ${SLACK_BUILD_URL}", after.getDefaultStartTemplate());
        assertEquals("ok", after.getDefaultSuccessTemplate());
        assertEquals("fail", after.getDefaultFailureTemplate());
        assertEquals("unstable", after.getDefaultUnstableTemplate());
        assertEquals("aborted", after.getDefaultAbortedTemplate());
        assertEquals("notbuilt", after.getDefaultNotBuiltTemplate());

        assertEquals(1, after.getRules().size());
        NotificationRule rule = after.getRules().get(0);
        assertEquals("dev/.*", rule.getJobNamePattern());
        assertTrue(rule.isNotifyStart());
        assertTrue(rule.isNotifySuccess());
        assertFalse(rule.isNotifyFailure());
    }
}
