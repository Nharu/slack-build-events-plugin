package io.jenkins.plugins.slackbuildevents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;

import hudson.util.FormValidation;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/** The unescaped {@code ${ENV,var="..."}} forms that must always be flagged with a warning. */
public class TemplateLintMustFlagTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void gitBranchEnvFormIsFlagged() {
        assertEquals(
                FormValidation.Kind.WARNING,
                TemplateLint.check("branch=${ENV,var=\"GIT_BRANCH\"}").kind);
    }

    @Test
    public void prDerivedWithoutEquivalentIsFlagged() {
        FormValidation fv = TemplateLint.check("title=${ENV,var=\"CHANGE_TITLE\"}");
        assertEquals(FormValidation.Kind.WARNING, fv.kind);
        assertThat(fv.getMessage(), containsString("CHANGE_TITLE"));
    }

    @Test
    public void otherUnsafeEnvIsFlaggedAsSoftNote() {
        FormValidation fv = TemplateLint.check("target=${ENV,var=\"DEPLOY_TARGET\"}");
        assertEquals(FormValidation.Kind.WARNING, fv.kind);
        assertThat(fv.getMessage(), containsString("DEPLOY_TARGET"));
    }
}
