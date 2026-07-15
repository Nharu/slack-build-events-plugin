package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.FreeStyleBuild;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jenkinsci.plugins.tokenmacro.DataBoundTokenMacro;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;

/**
 * The RENDER_ABORTED gate: when the pool is being torn down (forced shutdown), an interrupt-driven
 * ABORTED render must stay QUIET — the same as the transport sibling — instead of emitting a
 * RENDER_ABORTED WARNING that would just be restart-time noise. A running-pool interrupt still WARNs
 * (see {@code DispatchVisibilityTest#renderAbortedLogsWarning}); these two guards pin
 * {@code if (executor != null)} from both sides.
 *
 * <p>A capturing executor seam lets the test enqueue the runAttempt task, force the shutdown so the
 * executor field goes null, and only then run the task — so the ABORTED branch is exercised with the
 * pool already torn down.
 */
public class ForcedShutdownAbortQuietTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void forcedShutdownAbortStaysQuiet() throws Exception {
        FreeStyleBuild build = j.buildAndAssertSuccess(j.createFreeStyleProject("host"));
        SlackTestHelpers.addWebhookCredential("wh", "http://example.test/hook");

        CapturingExecutor executor = new CapturingExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory());
        TestWebhookSender sender = new TestWebhookSender();
        NotificationDispatcher dispatcher = NotificationDispatcher.get();
        dispatcher.installTestSeams(executor, scheduler, sender, System::currentTimeMillis);

        try (LogCapture logs = new LogCapture(FailureLogThrottle.class)) {
            // Enqueue a render that will abort (interrupting macro); the executor captures the task, unrun.
            dispatcher.dispatch(new NotificationContext(
                    build, TaskListener.NULL, null, "wh", "x=${SLACK_TEST_INTERRUPT}", "#000000", null,
                    EventType.SUCCESS));
            Runnable captured = executor.captured.get();
            assertNotNull("a runAttempt task was enqueued", captured);

            // Force shutdown: the executor field is torn down to null.
            NotificationDispatcher.stopAll();

            // Only now run the captured task, against the torn-down dispatcher.
            Thread.interrupted(); // clear the flag before
            captured.run();

            // The ABORTED branch actually ran (render restored the interrupt flag on this thread) ...
            assertTrue("aborted branch ran (interrupt flag restored)", Thread.interrupted());
            // ... but the forced-shutdown abort was gated to QUIET — no RENDER_ABORTED WARNING.
            assertEquals(0, logs.warningsContaining("RENDER_ABORTED"));
            assertEquals(0, sender.calls.get());
        }
    }

    private static ThreadFactory daemonFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread t = new Thread(runnable, "forced-shutdown-test-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /** Executor seam that captures the submitted task instead of running it. */
    private static final class CapturingExecutor extends ThreadPoolExecutor {
        final AtomicReference<Runnable> captured = new AtomicReference<>();

        CapturingExecutor() {
            super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), daemonFactory());
        }

        @Override
        public void execute(Runnable command) {
            captured.set(command);
        }
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
