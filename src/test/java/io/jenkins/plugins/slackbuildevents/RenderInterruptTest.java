package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.FreeStyleBuild;
import hudson.model.Run;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.tokenmacro.DataBoundTokenMacro;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;

/**
 * A macro-origin interrupt surfaces during render (wrapped in a {@code MacroEvaluationException}) as a
 * shutting-down signal: render must detect it, restore the worker thread's interrupt flag so the
 * dispatch pool's cooperative-cancellation contract holds, and abort with no body. {@code render()} is
 * package-private precisely so this flag restoration can be observed deterministically on the calling
 * thread (the async harness cannot see the pool worker's flag).
 */
public class RenderInterruptTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void interruptDuringRenderAbortsAndRestoresFlag() throws Exception {
        FreeStyleBuild build = j.buildAndAssertSuccess(j.createFreeStyleProject("host"));
        NotificationContext context = new NotificationContext(
                build, TaskListener.NULL, null, "wh", "x=${SLACK_TEST_INTERRUPT}", "#000000", null, EventType.SUCCESS);

        // Ensure the flag is clear before rendering.
        Thread.interrupted();
        RenderOutcome outcome = NotificationDispatcher.get().render(context);

        assertEquals(RenderCategory.ABORTED, outcome.category());
        assertNull("aborted render sends nothing", outcome.text());
        // The interrupt flag was restored by render; reading it here also clears it for later tests.
        assertTrue("interrupt flag restored after abort", Thread.interrupted());
    }

    /** Registered macro that throws a wrapped {@link InterruptedException} → render aborts (ABORTED). */
    @TestExtension
    public static class InterruptingMacro extends DataBoundTokenMacro {
        @Override
        public boolean acceptsMacroName(String name) {
            return "SLACK_TEST_INTERRUPT".equals(name);
        }

        @Override
        public String evaluate(AbstractBuild<?, ?> context, TaskListener listener, String macroName) {
            throw new RuntimeException(new InterruptedException("interrupted during evaluate"));
        }

        @Override
        public String evaluate(Run<?, ?> run, FilePath workspace, TaskListener listener, String macroName) {
            throw new RuntimeException(new InterruptedException("interrupted during evaluate"));
        }
    }
}
