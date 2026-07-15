package io.jenkins.plugins.slackbuildevents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
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
 * Second-order macro injection (secret exfiltration) is structurally closed by the macro-only
 * {@code expand()} render path.
 *
 * <p>A build parameter {@code GIT_BRANCH} whose value is itself a macro string
 * ({@code ${ENV,var="SOME_SECRET"}}) lets the old {@code expandAll()} leading env-substitution pass
 * splice that macro into the template and then evaluate it, leaking {@code SOME_SECRET}. The
 * macro-only {@code expand()} the render path now uses has no such pre-pass, so the parameter value
 * is never re-scanned as a macro. This drives the real {@link NotificationDispatcher#render}; the
 * contrast leg (asserting the old engine <em>does</em> leak) keeps it from passing vacuously.
 */
public class SecondOrderInjectionTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private static final String CANARY = "s3cr3t-canary";

    @Test
    public void plainParamMacroIsNotReEvaluatedByTheRenderPath() throws Exception {
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
        // Old engine: the env pre-pass splices the parameter value in, then the parser evaluates the
        // injected ${ENV,...} → the canary leaks. Proves the vector was real.
        assertThat(TokenMacro.expandAll(b, null, TaskListener.NULL, template), containsString(CANARY));
        // Real render path: no pre-pass, single evaluation → the canary never surfaces.
        assertThat(render(b, template), not(containsString(CANARY)));
    }

    /** Runs the production {@link NotificationDispatcher#render} on a synthetic context. */
    private static String render(FreeStyleBuild build, String template) {
        return NotificationDispatcher.get()
                .render(new NotificationContext(build, TaskListener.NULL, null, "wh", template, "#000000", null));
    }
}
