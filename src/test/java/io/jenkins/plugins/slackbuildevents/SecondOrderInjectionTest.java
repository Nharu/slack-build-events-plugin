package io.jenkins.plugins.slackbuildevents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Run;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Second-order macro injection (secret exfiltration) is structurally closed by candidate C.
 *
 * <p>A build parameter {@code GIT_BRANCH} whose value is itself a macro string
 * ({@code ${ENV,var="SOME_SECRET"}}) lets the old {@code expandAll()} leading env-substitution pass
 * splice that macro into the template and then evaluate it, leaking {@code SOME_SECRET}. The
 * macro-only {@code expand()} the render path now uses has no such pre-pass, so the parameter value
 * is never re-scanned as a macro. The contrast leg (asserting the old engine <em>does</em> leak)
 * keeps this from passing vacuously.
 */
public class SecondOrderInjectionTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private static final String CANARY = "s3cr3t-canary";

    @Test
    public void plainParamMacroIsNotReEvaluatedUnderCandidateC() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("soi");
        p.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("GIT_BRANCH", ""),
                new StringParameterDefinition("SOME_SECRET", "")));
        FreeStyleBuild b = j.assertBuildStatusSuccess(p.scheduleBuild2(
                0,
                new ParametersAction(
                        new StringParameterValue("GIT_BRANCH", "${ENV,var=\"SOME_SECRET\"}"),
                        new StringParameterValue("SOME_SECRET", CANARY))));

        String template = "branch=${GIT_BRANCH}";
        // Old engine: env pre-pass splices the parameter value in, then the parser evaluates the
        // injected ${ENV,...} → the canary leaks. Proves the vector was real.
        assertThat(TokenMacro.expandAll(b, null, TaskListener.NULL, template), containsString(CANARY));
        // Candidate C (render path): no pre-pass, single evaluation → canary never surfaces.
        assertThat(renderLikeDispatcher(b, template), not(containsString(CANARY)));
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
