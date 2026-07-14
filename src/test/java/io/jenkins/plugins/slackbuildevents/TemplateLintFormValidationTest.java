package io.jenkins.plugins.slackbuildevents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;

import hudson.util.FormValidation;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/** {@link FormValidation} shape: kinds, empty handling, safe-env exclusion, and aggregate combining. */
public class TemplateLintFormValidationTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void emptyOrNullIsOk() {
        assertEquals(FormValidation.Kind.OK, TemplateLint.check("").kind);
        assertEquals(FormValidation.Kind.OK, TemplateLint.check(null).kind);
    }

    @Test
    public void safeInfrastructureEnvIsNotFlagged() {
        assertEquals(
                FormValidation.Kind.OK,
                TemplateLint.check("job=${ENV,var=\"JOB_NAME\"} #${ENV,var=\"BUILD_NUMBER\"}").kind);
    }

    @Test
    public void warningAndPortabilityAreAggregatedWithWarningWinning() {
        // A tier-1 ENV-form warning plus a plain raw-fallback (ok) note in one field must aggregate to
        // WARNING while still surfacing both messages.
        FormValidation fv = TemplateLint.check("${ENV,var=\"GIT_BRANCH\"} and ${DEPLOY_TARGET}");
        assertEquals(FormValidation.Kind.WARNING, fv.kind);
        // An aggregate exposes its combined content via renderHtml() (getMessage() is null for it).
        String rendered = fv.renderHtml();
        assertThat(rendered, containsString("SLACK_GIT_BRANCH"));
        assertThat(rendered, containsString("DEPLOY_TARGET"));
    }
}
