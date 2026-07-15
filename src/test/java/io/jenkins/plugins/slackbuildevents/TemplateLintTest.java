package io.jenkins.plugins.slackbuildevents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;

import hudson.util.FormValidation;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/** Unit behavior of {@link TemplateLint#check(String)}: the ENV-form matcher and its skips. */
public class TemplateLintTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void flagsEnvFormWithSafeEquivalent() {
        FormValidation fv = TemplateLint.check("branch=${ENV,var=\"GIT_BRANCH\"}");
        assertEquals(FormValidation.Kind.WARNING, fv.kind);
        assertThat(fv.getMessage(), containsString("SLACK_GIT_BRANCH"));
    }

    @Test
    public void dollarDollarEscapeIsNotFlagged() {
        // $$ is a literal-dollar escape; the following ${ENV,...} never expands, so it is not a risk.
        assertEquals(FormValidation.Kind.OK, TemplateLint.check("x=$${ENV,var=\"GIT_BRANCH\"}").kind);
    }

    @Test
    public void singleQuotedEnvArgIsNotFlagged() {
        // Only double-quoted var="..." expands; a single-quoted form is not the canonical ENV form.
        assertEquals(FormValidation.Kind.OK, TemplateLint.check("x=${ENV,var='GIT_BRANCH'}").kind);
    }

    @Test
    public void plainUnknownVarIsAnOkPortabilityNote() {
        FormValidation fv = TemplateLint.check("deploy=${DEPLOY_TARGET}");
        assertEquals(FormValidation.Kind.OK, fv.kind);
        assertThat(fv.getMessage(), containsString("DEPLOY_TARGET"));
    }
}
