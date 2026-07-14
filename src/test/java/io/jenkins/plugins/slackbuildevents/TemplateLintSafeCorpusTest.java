package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertEquals;

import hudson.util.FormValidation;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/** No false positives: the built-in defaults, the {@code ${SLACK_*}} macros, and numeric {@code $N}. */
public class TemplateLintSafeCorpusTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void builtInDefaultsProduceNoWarning() {
        for (EventType event : EventType.values()) {
            FormValidation fv = TemplateLint.check(DefaultTemplates.forEvent(event));
            assertEquals("default template for " + event, FormValidation.Kind.OK, fv.kind);
        }
    }

    @Test
    public void slackMacrosAndNumericDollarsAreClean() {
        FormValidation fv = TemplateLint.check(
                "<${SLACK_BUILD_URL}console|Console> ${SLACK_GIT_BRANCH} ${SLACK_GIT_COMMIT} "
                        + "${SLACK_DEPLOYER} ${SLACK_DURATION} cost=$100 tier=$5");
        assertEquals(FormValidation.Kind.OK, fv.kind);
    }
}
