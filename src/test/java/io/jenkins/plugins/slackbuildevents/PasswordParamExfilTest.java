package io.jenkins.plugins.slackbuildevents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.PasswordParameterValue;
import hudson.model.Run;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Boundary characterization of the password-parameter leak (symptom 4) under candidate C alone.
 *
 * <p>Candidate C closes the <b>plain</b> {@code ${DEPLOY_PW}} form (an unregistered name now stops
 * expanding and the message falls back to raw text), but does <b>not</b> close the parser-stage
 * <b>ENV</b> form {@code ${ENV,var="DEPLOY_PW"}} — the {@code ENV} macro reads the plaintext straight
 * from the build environment. Fully closing the ENV form needs a render-path secret filter, tracked
 * as a separate follow-up. This test pins today's boundary so that follow-up can add a "filtered" leg.
 */
public class PasswordParamExfilTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private static final String CANARY = "s3cr3t-canary";

    @Test
    public void candidateCClosesPlainFormButNotEnvForm() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("pwexfil");
        p.addProperty(new ParametersDefinitionProperty(
                new PasswordParameterDefinition("DEPLOY_PW", "", "canary password parameter")));
        FreeStyleBuild b = j.assertBuildStatusSuccess(p.scheduleBuild2(
                0, new ParametersAction(new PasswordParameterValue("DEPLOY_PW", CANARY))));

        // ENV form: residual under candidate C (both engines leak) — the render-path secret filter is
        // a separate follow-up.
        assertThat(
                TokenMacro.expandAll(b, null, TaskListener.NULL, "pw=${ENV,var=\"DEPLOY_PW\"}"),
                containsString(CANARY));
        assertThat(renderLikeDispatcher(b, "pw=${ENV,var=\"DEPLOY_PW\"}"), containsString(CANARY));

        // Plain form: closed by candidate C (raw fallback), but leaked under the old engine.
        assertThat(
                TokenMacro.expandAll(b, null, TaskListener.NULL, "pw2=${DEPLOY_PW}"),
                containsString(CANARY));
        assertThat(renderLikeDispatcher(b, "pw2=${DEPLOY_PW}"), not(containsString(CANARY)));
    }

    /** Mirrors {@code NotificationDispatcher.render()}: macro-only expand with a raw-template fallback. */
    private static String renderLikeDispatcher(Run<?, ?> build, String template) throws Exception {
        try {
            return TokenMacro.expand(build, null, TaskListener.NULL, template);
        } catch (MacroEvaluationException e) {
            return template;
        }
    }
}
