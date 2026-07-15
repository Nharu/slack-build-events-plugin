package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertEquals;

import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jenkinsci.plugins.tokenmacro.DataBoundTokenMacro;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;

/**
 * #15 path-wide visibility: every dispatch-path failure now surfaces a WARNING at the default log
 * level (was FINE or nothing), routed through the rate-limiting throttle and labeled by category —
 * while Slack backpressure (429) stays quiet. WARNINGs are captured from the throttle's logger.
 */
public class DispatchVisibilityTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void credentialMissingLogsWarning() throws Exception {
        TestWebhookSender sender = new TestWebhookSender();
        SlackTestHelpers.installSeams(sender);
        // Default credential id points at a credential that does not exist.
        SlackTestHelpers.config().setDefaultWebhookCredentialId("ghost");
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("myjob", List.of("success"))));

        try (LogCapture logs = new LogCapture(FailureLogThrottle.class)) {
            j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
            SlackTestHelpers.awaitDispatch();
            assertEquals(1, logs.warningsContaining("CREDENTIAL_MISSING"));
            // Nothing was sent.
            assertEquals(0, sender.calls.get());
        }
    }

    @Test
    public void httpClientErrorLogsWarning() throws Exception {
        TestWebhookSender sender = new TestWebhookSender();
        sender.script(TestWebhookSender.status(404));
        installWithCredential(sender);

        try (LogCapture logs = new LogCapture(FailureLogThrottle.class)) {
            fireSuccessNotification();
            assertEquals(1, logs.warningsContaining("HTTP_CLIENT_ERROR"));
            assertEquals(1, logs.warningsContaining("HTTP 404"));
        }
    }

    @Test
    public void httpServerErrorLogsWarning() throws Exception {
        TestWebhookSender sender = new TestWebhookSender();
        sender.script(TestWebhookSender.status(500));
        installWithCredential(sender);

        try (LogCapture logs = new LogCapture(FailureLogThrottle.class)) {
            fireSuccessNotification();
            assertEquals(1, logs.warningsContaining("HTTP_SERVER_ERROR"));
        }
    }

    @Test
    public void transportErrorLogsWarning() throws Exception {
        WebhookSender throwing = new WebhookSender() {
            @Override
            Response send(String url, String jsonBody) throws IOException {
                throw new IOException("connection reset");
            }
        };
        installWithCredential(throwing);

        try (LogCapture logs = new LogCapture(FailureLogThrottle.class)) {
            fireSuccessNotification();
            assertEquals(1, logs.warningsContaining("TRANSPORT_ERROR"));
        }
    }

    @Test
    public void rateLimit429IsQuiet() throws Exception {
        TestWebhookSender sender = new TestWebhookSender();
        sender.script(TestWebhookSender.status429RetryNow());
        installWithCredential(sender);
        SlackTestHelpers.config().setMaxRetriesOn429(0); // no retry budget → terminal 429

        try (LogCapture logs = new LogCapture(FailureLogThrottle.class)) {
            fireSuccessNotification();
            // 429 is normal backpressure: no WARNING at all.
            assertEquals(0, logs.warningCount());
        }
    }

    @Test
    public void renderDegradedLogsWarning() throws Exception {
        TestWebhookSender sender = new TestWebhookSender();
        installWithCredential(sender);
        NotificationRule rule = SlackTestHelpers.rule("myjob", List.of("success"));
        rule.setSuccessTemplate("bad=${DEFINITELY_UNKNOWN}");
        SlackTestHelpers.config().setRules(List.of(rule));

        try (LogCapture logs = new LogCapture(FailureLogThrottle.class)) {
            j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
            SlackTestHelpers.awaitDispatch();
            assertEquals(1, logs.warningsContaining("RENDER_DEGRADED"));
        }
    }

    @Test
    public void renderFallbackLogsWarning() throws Exception {
        TestWebhookSender sender = new TestWebhookSender();
        installWithCredential(sender);
        NotificationRule rule = SlackTestHelpers.rule("myjob", List.of("success"));
        rule.setSuccessTemplate("boom=${SLACK_TEST_RUNTIME}");
        SlackTestHelpers.config().setRules(List.of(rule));

        try (LogCapture logs = new LogCapture(FailureLogThrottle.class)) {
            j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
            SlackTestHelpers.awaitDispatch();
            assertEquals(1, logs.warningsContaining("RENDER_FALLBACK"));
        }
    }

    @Test
    public void unexpectedErrorLogsWarning() throws Exception {
        WebhookSender throwing = new WebhookSender() {
            @Override
            Response send(String url, String jsonBody) {
                throw new IllegalStateException("unexpected boom");
            }
        };
        installWithCredential(throwing);

        try (LogCapture logs = new LogCapture(FailureLogThrottle.class)) {
            fireSuccessNotification();
            assertEquals(1, logs.warningsContaining("UNEXPECTED_ERROR"));
        }
    }

    @Test
    public void transportFailureDoesNotLeakWebhookSecret() throws Exception {
        // A well-formed URL that passes the send-time policy, so the flow reaches send(); the sender then
        // fails with the raw URL — including its secret token — embedded in the exception message, as a
        // real HTTP client can. The scrub must keep the failure visible while removing the secret from the
        // WARNING. No category is asserted: the guarantee must hold wherever the scrub lands.
        String secretUrl = "http://hooks.slack.test/services/T0/B1/SUPERSECRETTOKEN";
        WebhookSender leaking = new WebhookSender() {
            @Override
            Response send(String url, String jsonBody) throws IOException {
                throw new IOException("connection failed reaching " + url);
            }
        };
        SlackTestHelpers.installSeams(leaking);
        SlackTestHelpers.addWebhookCredential("wh", secretUrl);
        SlackTestHelpers.config().setDefaultWebhookCredentialId("wh");

        try (LogCapture logs = new LogCapture(FailureLogThrottle.class)) {
            fireSuccessNotification();
            // The failure is surfaced (the whole point of #15) ...
            assertEquals(1, logs.warningCount());
            // ... but the secret token and the credential path never reach the log.
            assertEquals(0, logs.warningsContaining("SUPERSECRETTOKEN"));
            assertEquals(0, logs.warningsContaining("/services/"));
        }
    }

    @Test
    public void queueSaturatedLogsWarning() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        ThreadPoolExecutor saturated =
                new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(1), daemonFactory());
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory());
        TestWebhookSender sender = new TestWebhookSender();
        sender.blockOn(gate); // the first attempt occupies the sole worker until the gate opens
        NotificationDispatcher dispatcher = NotificationDispatcher.get();
        dispatcher.installTestSeams(saturated, scheduler, sender, System::currentTimeMillis);
        SlackTestHelpers.addWebhookCredential("wh", "http://example.test/hook");

        FreeStyleBuild build = j.buildAndAssertSuccess(j.createFreeStyleProject("host"));
        try (LogCapture logs = new LogCapture(FailureLogThrottle.class)) {
            // 1 occupies the worker, 1 fills the queue, the rest overflow a still-running (not shut down)
            // pool → genuine saturation, collapsed by the throttle to one WARNING.
            for (int i = 0; i < 4; i++) {
                dispatcher.dispatch(new NotificationContext(
                        build, TaskListener.NULL, null, "wh", "text", "#000000", null, EventType.SUCCESS));
            }
            assertEquals(1, logs.warningsContaining("QUEUE_SATURATED"));
        } finally {
            gate.countDown();
            dispatcher.awaitAllDispatched(15, TimeUnit.SECONDS);
        }
    }

    @Test
    public void signatureStableAcrossMacroWordingChanges() throws Exception {
        TestWebhookSender sender = new TestWebhookSender();
        installWithCredential(sender);
        NotificationRule rule = SlackTestHelpers.rule("myjob", List.of("success"));
        rule.setSuccessTemplate("x=${SLACK_TEST_REWORDING}");
        SlackTestHelpers.config().setRules(List.of(rule));
        FreeStyleProject project = j.createFreeStyleProject("myjob");

        try (LogCapture logs = new LogCapture(FailureLogThrottle.class)) {
            // Same job, same template, same failure — but the macro words its exception differently each
            // time, as a token-macro upgrade or a message carrying per-build text would. The signature is
            // built from the template digest and the root cause class, so the wording cannot split it.
            j.buildAndAssertSuccess(project);
            SlackTestHelpers.awaitDispatch();
            j.buildAndAssertSuccess(project);
            SlackTestHelpers.awaitDispatch();

            assertEquals("one recurring failure stays one suppression bucket", 1, logs.warningsContaining("RENDER_DEGRADED"));
        }
    }

    @Test
    public void warningBodyStillNamesTheToken() throws Exception {
        TestWebhookSender sender = new TestWebhookSender();
        installWithCredential(sender);
        NotificationRule rule = SlackTestHelpers.rule("myjob", List.of("success"));
        rule.setSuccessTemplate("bad=${DEFINITELY_UNKNOWN}");
        SlackTestHelpers.config().setRules(List.of(rule));

        try (LogCapture logs = new LogCapture(FailureLogThrottle.class)) {
            j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
            SlackTestHelpers.awaitDispatch();

            // Dropping the hint from the signature must not cost the operator the diagnosis.
            assertEquals(1, logs.warningsContaining("token 'DEFINITELY_UNKNOWN'"));
        }
    }

    @Test
    public void hintIsNotRatifiedUnlessPresentInOurTemplate() throws Exception {
        TestWebhookSender sender = new TestWebhookSender();
        installWithCredential(sender);
        NotificationRule rule = SlackTestHelpers.rule("myjob", List.of("success"));
        rule.setSuccessTemplate("x=${SLACK_TEST_MISNAMING}");
        SlackTestHelpers.config().setRules(List.of(rule));

        try (LogCapture logs = new LogCapture(FailureLogThrottle.class)) {
            j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
            SlackTestHelpers.awaitDispatch();

            // The failure is still signaled ...
            assertEquals(1, logs.warningsContaining("RENDER_DEGRADED"));
            // ... but a name our template never references is not presented as one of our tokens.
            assertEquals(0, logs.warningsContaining("token 'GHOST_TOKEN'"));
        }
    }

    @Test
    public void hintCannotForgeLogLines() throws Exception {
        TestWebhookSender sender = new TestWebhookSender();
        installWithCredential(sender);
        NotificationRule rule = SlackTestHelpers.rule("myjob", List.of("success"));
        rule.setSuccessTemplate("x=${SLACK_TEST_FORGING}");
        SlackTestHelpers.config().setRules(List.of(rule));

        try (LogCapture logs = new LogCapture(FailureLogThrottle.class)) {
            j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
            SlackTestHelpers.awaitDispatch();

            // The failure is still signaled ...
            assertEquals(1, logs.warningsContaining("RENDER_DEGRADED"));
            // ... but a quoted span carrying control characters is not a macro name, so nothing crafted
            // ever reaches the headline's token slot to break it into a forged line.
            assertEquals(0, logs.warningsContaining("token '"));
        }
    }

    @Test
    public void everyFailureCategoryIsAccountedFor() {
        // Tripwire: a new FailureCategory added without updating this set (and giving it visibility
        // coverage) fails the build, so no dispatch-failure class slips in unsignaled.
        EnumSet<FailureCategory> asserted = EnumSet.of(
                FailureCategory.CREDENTIAL_MISSING,
                FailureCategory.RENDER_DEGRADED,
                FailureCategory.RENDER_FALLBACK,
                FailureCategory.RENDER_ABORTED,
                FailureCategory.HTTP_CLIENT_ERROR,
                FailureCategory.HTTP_SERVER_ERROR,
                FailureCategory.TRANSPORT_ERROR,
                FailureCategory.UNEXPECTED_ERROR,
                FailureCategory.QUEUE_SATURATED);
        assertEquals(EnumSet.allOf(FailureCategory.class), asserted);
    }

    private static ThreadFactory daemonFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread t = new Thread(runnable, "dispatch-visibility-test-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    private void installWithCredential(WebhookSender sender) throws Exception {
        SlackTestHelpers.installSeams(sender);
        SlackTestHelpers.addWebhookCredential("wh", "http://example.test/hook");
        SlackTestHelpers.config().setDefaultWebhookCredentialId("wh");
    }

    private void fireSuccessNotification() throws Exception {
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("myjob", List.of("success"))));
        j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
        SlackTestHelpers.awaitDispatch();
    }

    /** Words its exception differently on every call, for one logically identical failure. */
    @TestExtension
    public static class RewordingMacro extends DataBoundTokenMacro {
        private static final AtomicInteger CALLS = new AtomicInteger();

        @Override
        public boolean acceptsMacroName(String name) {
            return "SLACK_TEST_REWORDING".equals(name);
        }

        @Override
        public String evaluate(AbstractBuild<?, ?> context, TaskListener listener, String macroName)
                throws MacroEvaluationException {
            throw reworded();
        }

        @Override
        public String evaluate(Run<?, ?> run, FilePath workspace, TaskListener listener, String macroName)
                throws MacroEvaluationException {
            throw reworded();
        }

        private static MacroEvaluationException reworded() {
            int n = CALLS.incrementAndGet();
            return new MacroEvaluationException("variant " + n + ": could not expand 'TOKEN_" + n + "' this build");
        }
    }

    /** Names a token in its message that our template never references. */
    @TestExtension
    public static class MisnamingMacro extends DataBoundTokenMacro {
        @Override
        public boolean acceptsMacroName(String name) {
            return "SLACK_TEST_MISNAMING".equals(name);
        }

        @Override
        public String evaluate(AbstractBuild<?, ?> context, TaskListener listener, String macroName)
                throws MacroEvaluationException {
            throw new MacroEvaluationException("Unrecognized macro 'GHOST_TOKEN' in 'some other template'");
        }

        @Override
        public String evaluate(Run<?, ?> run, FilePath workspace, TaskListener listener, String macroName)
                throws MacroEvaluationException {
            throw new MacroEvaluationException("Unrecognized macro 'GHOST_TOKEN' in 'some other template'");
        }
    }

    /** Puts CR/LF inside the quoted span, attempting to forge a log line through the hint. */
    @TestExtension
    public static class LogForgingMacro extends DataBoundTokenMacro {
        private static final String FORGED = "Unrecognized macro 'X\nSEVERE: forged log line' in 'x=${X}'";

        @Override
        public boolean acceptsMacroName(String name) {
            return "SLACK_TEST_FORGING".equals(name);
        }

        @Override
        public String evaluate(AbstractBuild<?, ?> context, TaskListener listener, String macroName)
                throws MacroEvaluationException {
            throw new MacroEvaluationException(FORGED);
        }

        @Override
        public String evaluate(Run<?, ?> run, FilePath workspace, TaskListener listener, String macroName)
                throws MacroEvaluationException {
            throw new MacroEvaluationException(FORGED);
        }
    }

    /** Registered macro that throws a plain {@link RuntimeException} → both passes throw → FALLBACK. */
    @TestExtension
    public static class RuntimeFailingMacro extends DataBoundTokenMacro {
        @Override
        public boolean acceptsMacroName(String name) {
            return "SLACK_TEST_RUNTIME".equals(name);
        }

        @Override
        public String evaluate(AbstractBuild<?, ?> context, TaskListener listener, String macroName) {
            throw new RuntimeException("runtime boom");
        }

        @Override
        public String evaluate(Run<?, ?> run, FilePath workspace, TaskListener listener, String macroName) {
            throw new RuntimeException("runtime boom");
        }
    }
}
