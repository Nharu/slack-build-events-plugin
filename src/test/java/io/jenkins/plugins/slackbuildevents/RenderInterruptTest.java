package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.FreeStyleBuild;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import org.jenkinsci.plugins.tokenmacro.DataBoundTokenMacro;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;

/**
 * Interrupt classification on both dispatch surfaces: only this thread's own interrupt state — a raised
 * flag, or a throwable that <em>is</em> an {@link InterruptedException} — aborts. An
 * {@code InterruptedException} merely carried in a cause chain is an ordinary failure whose notification
 * is still sent: token-macro wraps whatever a macro throws, so a genuine in-macro interrupt and an
 * unrelated library failure that happens to carry one are indistinguishable, and aborting on the chain
 * silently drops sendable notifications.
 *
 * <p>{@code render()} is package-private precisely so the flag can be observed deterministically on the
 * calling thread (the async harness cannot see a pool worker's flag).
 */
public class RenderInterruptTest {

    private static final String CREDENTIAL_ID = "wh";

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Before
    public void clearInterruptBeforeTest() {
        Thread.interrupted();
    }

    @After
    public void clearInterruptAfterTest() {
        // The JUnit thread is shared across tests: never leave a flag raised on it.
        Thread.interrupted();
    }

    @Test
    public void spuriousWrappedInterruptedExceptionIsNotAnAbort() throws Exception {
        TestWebhookSender sender = new TestWebhookSender();
        NotificationContext context = installAndBuild(sender, "x=${SLACK_TEST_WRAPPED_IE}");

        RenderOutcome outcome = NotificationDispatcher.get().render(context);

        assertEquals(RenderCategory.FALLBACK, outcome.category());
        assertNotNull("a spurious wrapped interrupt still produces a body", outcome.text());
        assertFalse("nothing interrupted this thread", Thread.currentThread().isInterrupted());

        NotificationDispatcher.get().dispatch(context).get();
        assertEquals("the notification is still delivered", 1, sender.calls.get());
    }

    @Test
    public void spuriousInterruptedExceptionNestedInMacroExceptionDegradesAndSends() throws Exception {
        TestWebhookSender sender = new TestWebhookSender();
        NotificationContext context = installAndBuild(sender, "x=${SLACK_TEST_MACRO_IE}");

        RenderOutcome outcome = NotificationDispatcher.get().render(context);

        assertEquals(RenderCategory.DEGRADED, outcome.category());
        assertNotNull("a degraded render still produces a body", outcome.text());
        assertFalse("nothing interrupted this thread", Thread.currentThread().isInterrupted());

        NotificationDispatcher.get().dispatch(context).get();
        assertEquals("the notification is still delivered", 1, sender.calls.get());
    }

    @Test
    public void checkedInterruptedExceptionFromMacroIsSentNotAborted() throws Exception {
        // Pins the accepted trade-off: a genuine shutdown interrupt raised inside a macro reaches render
        // wrapped and flag-cleared, i.e. identical to the spurious case, so it is handled as an ordinary
        // failure and best-effort sent rather than losing every spurious one to a silent drop.
        TestWebhookSender sender = new TestWebhookSender();
        NotificationContext context = installAndBuild(sender, "x=${SLACK_TEST_CHECKED_IE}");

        RenderOutcome outcome = NotificationDispatcher.get().render(context);

        assertEquals(RenderCategory.FALLBACK, outcome.category());
        assertNotNull("a wrapped checked interrupt still produces a body", outcome.text());
        assertFalse("throwing an InterruptedException clears the flag", Thread.currentThread().isInterrupted());

        NotificationDispatcher.get().dispatch(context).get();
        assertEquals("the notification is still delivered", 1, sender.calls.get());
    }

    @Test
    public void interruptFlagSetAbortsAndRestoresFlag() throws Exception {
        TestWebhookSender sender = new TestWebhookSender();
        // The same macro as the spurious-wrapped test: the raised flag is the only difference, and it is
        // what flips the outcome from FALLBACK to ABORTED.
        NotificationContext context = installAndBuild(sender, "x=${SLACK_TEST_WRAPPED_IE}");

        Thread.currentThread().interrupt();
        RenderOutcome outcome = NotificationDispatcher.get().render(context);

        assertEquals(RenderCategory.ABORTED, outcome.category());
        assertNull("an aborted render sends nothing", outcome.text());
        // Reading it here also clears it for the tests that follow.
        assertTrue("interrupt flag restored after abort", Thread.interrupted());
    }

    @Test
    public void abortedRenderIsLoggedViaSelfInterruptingMacro() throws Exception {
        // A macro that raises its own thread's flag and then throws something that is NOT an
        // InterruptedException: the flag survives the throw, so the render path sees a genuine interrupt
        // and aborts — deterministically, with no shutdown machinery involved.
        TestWebhookSender sender = new TestWebhookSender();
        NotificationContext context = installAndBuild(sender, "x=${SLACK_TEST_SELF_INTERRUPT}");

        try (LogCapture logs = new LogCapture(FailureLogThrottle.class)) {
            NotificationDispatcher.get().dispatch(context).get();

            assertEquals("the abort leaves an operator-visible trace", 1, logs.recordsContaining("RENDER_ABORTED"));
            assertEquals("an aborted render sends nothing", 0, sender.calls.get());
        }
    }

    @Test
    public void rawInterruptedExceptionFromSendStaysQuiet() throws Exception {
        TestWebhookSender sender = new TestWebhookSender();
        sender.throwOn(new InterruptedException("pool shutting down mid-send"));
        NotificationContext context = installAndBuild(sender, "plain body");

        try (LogCapture logs = new LogCapture(FailureLogThrottle.class)) {
            NotificationDispatcher.get().dispatch(context).get();

            assertEquals("a genuine send interrupt is teardown, not a failure", 0, logs.warningCount());
        }
    }

    @Test
    public void interruptedExceptionCarriedByIoExceptionIsATransportFailure() throws Exception {
        // A real transport failure that merely carries an InterruptedException in its chain used to be
        // swallowed as an interrupt (and forged the flag); it is a genuine failure and must be signaled.
        TestWebhookSender sender = new TestWebhookSender();
        sender.throwOn(new IOException("connection reset", new InterruptedException("unrelated")));
        NotificationContext context = installAndBuild(sender, "plain body");

        try (LogCapture logs = new LogCapture(FailureLogThrottle.class)) {
            NotificationDispatcher.get().dispatch(context).get();

            assertEquals(1, logs.warningsContaining("TRANSPORT_ERROR"));
        }
    }

    private NotificationContext installAndBuild(WebhookSender sender, String template) throws Exception {
        SlackTestHelpers.installSeams(sender);
        SlackTestHelpers.addWebhookCredential(CREDENTIAL_ID, "http://example.test/hook");
        FreeStyleBuild build = j.buildAndAssertSuccess(j.createFreeStyleProject("host"));
        return new NotificationContext(
                build, TaskListener.NULL, null, CREDENTIAL_ID, template, "#000000", null, EventType.SUCCESS);
    }

    /** Throws a {@link RuntimeException} wrapping an {@link InterruptedException} — the spurious case. */
    @TestExtension
    public static class WrappedInterruptMacro extends DataBoundTokenMacro {
        @Override
        public boolean acceptsMacroName(String name) {
            return "SLACK_TEST_WRAPPED_IE".equals(name);
        }

        @Override
        public String evaluate(AbstractBuild<?, ?> context, TaskListener listener, String macroName) {
            throw new RuntimeException(new InterruptedException("not a real interrupt"));
        }

        @Override
        public String evaluate(Run<?, ?> run, FilePath workspace, TaskListener listener, String macroName) {
            throw new RuntimeException(new InterruptedException("not a real interrupt"));
        }
    }

    /** Throws a {@link MacroEvaluationException} whose cause is an {@link InterruptedException}. */
    @TestExtension
    public static class MacroExceptionCarryingInterruptMacro extends DataBoundTokenMacro {
        @Override
        public boolean acceptsMacroName(String name) {
            return "SLACK_TEST_MACRO_IE".equals(name);
        }

        @Override
        public String evaluate(AbstractBuild<?, ?> context, TaskListener listener, String macroName)
                throws MacroEvaluationException {
            throw new MacroEvaluationException("boom", new InterruptedException("not a real interrupt"));
        }

        @Override
        public String evaluate(Run<?, ?> run, FilePath workspace, TaskListener listener, String macroName)
                throws MacroEvaluationException {
            throw new MacroEvaluationException("boom", new InterruptedException("not a real interrupt"));
        }
    }

    /** Throws a checked {@link InterruptedException} — token-macro wraps it and the flag stays clear. */
    @TestExtension
    public static class CheckedInterruptMacro extends DataBoundTokenMacro {
        @Override
        public boolean acceptsMacroName(String name) {
            return "SLACK_TEST_CHECKED_IE".equals(name);
        }

        @Override
        public String evaluate(AbstractBuild<?, ?> context, TaskListener listener, String macroName)
                throws InterruptedException {
            throw new InterruptedException("interrupted inside evaluate");
        }

        @Override
        public String evaluate(Run<?, ?> run, FilePath workspace, TaskListener listener, String macroName)
                throws InterruptedException {
            throw new InterruptedException("interrupted inside evaluate");
        }
    }

    /** Raises its own thread's interrupt flag, then throws a non-interrupt throwable so the flag survives. */
    @TestExtension
    public static class SelfInterruptingMacro extends DataBoundTokenMacro {
        @Override
        public boolean acceptsMacroName(String name) {
            return "SLACK_TEST_SELF_INTERRUPT".equals(name);
        }

        @Override
        public String evaluate(AbstractBuild<?, ?> context, TaskListener listener, String macroName) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while evaluating");
        }

        @Override
        public String evaluate(Run<?, ?> run, FilePath workspace, TaskListener listener, String macroName) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while evaluating");
        }
    }
}
