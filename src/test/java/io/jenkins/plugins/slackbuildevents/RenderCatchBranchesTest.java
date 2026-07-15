package io.jenkins.plugins.slackbuildevents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.FreeStyleBuild;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.jenkinsci.plugins.tokenmacro.DataBoundTokenMacro;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;

/**
 * The typed catch branches of {@link NotificationDispatcher#render}: an I/O error warns (without
 * logging any rendered output) and falls back to the raw template; an interruption restores the
 * thread's interrupt flag and falls back to raw (it does not abort the in-progress send).
 */
public class RenderCatchBranchesTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private static final String IO_TEMPLATE = "x=${THROW_IO}";
    private static final String INT_TEMPLATE = "x=${THROW_INTERRUPT}";

    @Test
    public void ioErrorFallsBackToRawWithWarningAndNoRenderedOutput() throws Exception {
        FreeStyleBuild b = j.buildAndAssertSuccess(j.createFreeStyleProject("io"));
        List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
        Handler handler = attach(NotificationDispatcher.class.getName(), records);
        String out;
        try {
            out = render(b, IO_TEMPLATE);
        } finally {
            Logger.getLogger(NotificationDispatcher.class.getName()).removeHandler(handler);
        }

        // Raw fallback: the template text is returned verbatim.
        assertEquals(IO_TEMPLATE, out);
        LogRecord warning = records.stream()
                .filter(r -> r.getLevel() == Level.WARNING)
                .filter(r -> r.getMessage() != null && r.getMessage().contains("I/O error"))
                .findFirst()
                .orElse(null);
        assertNotNull("an I/O WARNING should be logged", warning);
        // The rendered output must never be logged (it could carry expanded secret values).
        assertThat(warning.getMessage(), not(containsString("${THROW_IO}")));
    }

    @Test
    public void interruptionRestoresFlagAndFallsBackToRaw() throws Exception {
        FreeStyleBuild b = j.buildAndAssertSuccess(j.createFreeStyleProject("int"));
        Thread.interrupted(); // clear any stray flag before the test

        String out = render(b, INT_TEMPLATE);

        // render() restores the interrupt flag; read-and-clear so it does not leak to later tests.
        boolean flagged = Thread.interrupted();
        assertTrue("render() must restore the interrupt flag", flagged);
        // The message falls back to raw. The in-progress send is NOT aborted — deliberately not asserted.
        assertEquals(INT_TEMPLATE, out);
    }

    /** Runs the production {@link NotificationDispatcher#render} on a synthetic context. */
    private static String render(FreeStyleBuild build, String template) {
        return NotificationDispatcher.get()
                .render(new NotificationContext(build, TaskListener.NULL, null, "wh", template, "#000000", null));
    }

    private static Handler attach(String loggerName, List<LogRecord> sink) {
        Logger logger = Logger.getLogger(loggerName);
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                sink.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        return handler;
    }

    /** Test-only macro that fails with an {@link IOException}, to drive render()'s I/O catch branch. */
    @TestExtension("ioErrorFallsBackToRawWithWarningAndNoRenderedOutput")
    public static class ThrowingIoMacro extends DataBoundTokenMacro {
        @Override
        public boolean acceptsMacroName(String name) {
            return "THROW_IO".equals(name);
        }

        @Override
        public String evaluate(AbstractBuild<?, ?> context, TaskListener listener, String macroName)
                throws IOException {
            throw new IOException("boom-io");
        }

        @Override
        public String evaluate(Run<?, ?> run, FilePath workspace, TaskListener listener, String macroName)
                throws IOException {
            throw new IOException("boom-io");
        }
    }

    /** Test-only macro that fails with an {@link InterruptedException}, to drive render()'s interrupt branch. */
    @TestExtension("interruptionRestoresFlagAndFallsBackToRaw")
    public static class ThrowingInterruptMacro extends DataBoundTokenMacro {
        @Override
        public boolean acceptsMacroName(String name) {
            return "THROW_INTERRUPT".equals(name);
        }

        @Override
        public String evaluate(AbstractBuild<?, ?> context, TaskListener listener, String macroName)
                throws InterruptedException {
            throw new InterruptedException("boom-int");
        }

        @Override
        public String evaluate(Run<?, ?> run, FilePath workspace, TaskListener listener, String macroName)
                throws InterruptedException {
            throw new InterruptedException("boom-int");
        }
    }
}
