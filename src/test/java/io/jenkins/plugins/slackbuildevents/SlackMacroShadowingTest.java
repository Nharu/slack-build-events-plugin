package io.jenkins.plugins.slackbuildevents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * {@code ${SLACK_*}} macro shadowing (the default-template escaping bypass) is structurally closed by
 * the macro-only {@code expand()} render path.
 *
 * <p>A build parameter named exactly {@code SLACK_GIT_BRANCH} let the old {@code expandAll()} env
 * pre-pass substitute the raw, unescaped parameter value for {@code ${SLACK_GIT_BRANCH}} before the
 * plugin's escaping macro ever ran. Under the macro-only {@code expand()} render path the token is
 * always handled by the plugin macro, so the same-named parameter has no effect: the hostile build
 * renders exactly like a benign build with no such parameter.
 */
public class SlackMacroShadowingTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private static final String HOSTILE = "<https://evil|shadow>";

    @Test
    public void sameNamedParameterCannotShadowPluginMacroUnderCandidateC() throws Exception {
        FreeStyleProject hostileProject = j.createFreeStyleProject("shadow-hostile");
        hostileProject.addProperty(
                new ParametersDefinitionProperty(new StringParameterDefinition("SLACK_GIT_BRANCH", "")));
        FreeStyleBuild hostile = j.assertBuildStatusSuccess(hostileProject.scheduleBuild2(
                0, new ParametersAction(new StringParameterValue("SLACK_GIT_BRANCH", HOSTILE))));

        FreeStyleBuild benign = j.buildAndAssertSuccess(j.createFreeStyleProject("shadow-benign"));

        String template = "branch=${SLACK_GIT_BRANCH}";
        String hostileExpand = TokenMacro.expand(hostile, null, TaskListener.NULL, template);
        String benignExpand = TokenMacro.expand(benign, null, TaskListener.NULL, template);

        // Macro-only expand(): the same-named parameter has no effect — hostile renders like benign, and the
        // raw link markup never reaches the message.
        assertThat(hostileExpand, is(benignExpand));
        assertThat(hostileExpand, not(containsString("<https")));

        // Contrast: the old engine substitutes the raw parameter value and leaks the link markup.
        assertThat(
                TokenMacro.expandAll(hostile, null, TaskListener.NULL, template),
                containsString(HOSTILE));
    }
}
