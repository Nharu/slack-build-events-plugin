package io.jenkins.plugins.slackbuildevents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;

import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.FreeStyleBuild;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.tokenmacro.DataBoundTokenMacro;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;

/**
 * Render-time behavior of the start-branch hint: single-point escaping, and the render-scoped
 * ThreadLocal contract (R1) — no leak across a pooled worker even on the raw-template catch path,
 * and correct re-resolution after a retry hand-off.
 */
public class StartBranchRenderTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private FreeStyleBuild build;

    @Before
    public void setUp() throws Exception {
        SlackTestHelpers.addWebhookCredential("wh", "http://example.test/hook");
        build = j.buildAndAssertSuccess(j.createFreeStyleProject("host"));
    }

    /** The hint flows through {@code branch()} and is escaped once at the macro emit point. */
    @Test
    public void startHintIsEscapedAtEmitPoint() throws Exception {
        GitMacroSupport.setStartBranchHint("origin/<https://evil|click>");
        try {
            assertEquals(
                    "origin/&lt;https://evil|click&gt;",
                    TokenMacro.expandAll(build, null, TaskListener.NULL, "${SLACK_GIT_BRANCH}"));
        } finally {
            GitMacroSupport.clearStartBranchHint();
        }
    }

    /**
     * R1(a): a first notification whose render fails into the raw-template catch path must still
     * clear the hint in {@code finally}, so a second notification on the same pooled worker does
     * not inherit the leaked branch.
     */
    @Test
    public void catchPathClearsHintNoLeakAcrossSameWorker() throws Exception {
        TestWebhookSender sender = new TestWebhookSender();
        ThreadFactory daemon = daemonFactory();
        ExecutorService ex = Executors.newSingleThreadExecutor(daemon);
        ScheduledExecutorService sch = Executors.newSingleThreadScheduledExecutor(daemon);
        NotificationDispatcher d = NotificationDispatcher.get();
        d.installTestSeams(ex, sch, sender, System::currentTimeMillis);

        // A: hint set, but the template throws so render takes the raw-template fallback.
        d.dispatch(new NotificationContext(
                build, TaskListener.NULL, null, "wh", "${SLACK_TEST_THROW}", "#000000", "leakybranch", EventType.START));
        d.awaitAllDispatched(15, TimeUnit.SECONDS);

        // B: no hint; must resolve via the normal chain (N/A) on the same single worker.
        d.dispatch(new NotificationContext(
                build, TaskListener.NULL, null, "wh", "branch=${SLACK_GIT_BRANCH}", "#000000", null, EventType.START));
        d.awaitAllDispatched(15, TimeUnit.SECONDS);

        String bodyB = text(sender.bodies.get(1));
        assertThat(bodyB, containsString("branch=N/A"));
        assertThat(bodyB, not(containsString("leakybranch")));
    }

    /**
     * R1(b): a retried START render (possibly on a different pooled worker) still resolves the
     * start branch, because render re-installs the hint from the context on every attempt.
     */
    @Test
    public void retryReresolvesStartHint() throws Exception {
        TestWebhookSender sender = new TestWebhookSender();
        SlackTestHelpers.installSeams(sender);
        SlackTestHelpers.config().setMaxRetriesOn429(1);
        sender.script(TestWebhookSender.status429RetryNow(), TestWebhookSender.status(200));

        NotificationDispatcher.get()
                .dispatch(new NotificationContext(
                        build, TaskListener.NULL, null, "wh", "branch=${SLACK_GIT_BRANCH}", "#000000", "retrybranch",
                        EventType.START));
        SlackTestHelpers.awaitDispatch();

        assertEquals("initial attempt + one retry", 2, sender.calls.get());
        assertThat(text(sender.bodies.get(1)), containsString("branch=retrybranch"));
    }

    private static ThreadFactory daemonFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread t = new Thread(runnable, "start-branch-test-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    private static String text(String body) {
        return JSONObject.fromObject(body).getJSONArray("attachments").getJSONObject(0).getString("text");
    }

    /** Test-only macro that always fails, to drive render() into its raw-template catch path. */
    @TestExtension
    public static class ThrowingMacro extends DataBoundTokenMacro {
        @Override
        public boolean acceptsMacroName(String name) {
            return "SLACK_TEST_THROW".equals(name);
        }

        @Override
        public String evaluate(AbstractBuild<?, ?> context, TaskListener listener, String macroName) {
            throw new RuntimeException("boom");
        }

        @Override
        public String evaluate(Run<?, ?> run, FilePath workspace, TaskListener listener, String macroName) {
            throw new RuntimeException("boom");
        }
    }
}
