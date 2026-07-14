package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertEquals;

import hudson.model.FreeStyleBuild;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Golden parity: the six built-in default templates render byte-identically under the macro-only
 * {@code expand()} the render path now uses and the previous {@code expandAll()}. This pins that
 * switching engines (candidate C) does not change any default message — the defaults reference only
 * registered macros ({@code ${SLACK_*}}, {@code ${ENV,var="..."}}), which both engines evaluate the
 * same way.
 */
public class RenderGoldenTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void defaultTemplatesRenderIdenticallyUnderExpandAndExpandAll() throws Exception {
        FreeStyleBuild build = j.buildAndAssertSuccess(j.createFreeStyleProject("golden"));
        for (EventType event : EventType.values()) {
            String template = DefaultTemplates.forEvent(event);
            String expandAll = TokenMacro.expandAll(build, null, TaskListener.NULL, template);
            String expand = TokenMacro.expand(build, null, TaskListener.NULL, template);
            assertEquals(
                    "default template for " + event + " must render identically under expand vs expandAll",
                    expandAll,
                    expand);
        }
    }
}
