package io.jenkins.plugins.slackbuildevents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;

import hudson.model.FreeStyleProject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * The migration matcher (which plain references will raw-fall-back under the macro-only expand() render
 * startup sweep that names them, and the render-path WARNING that fires once per distinct template.
 */
public class TemplateMigrationSweepTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private TestWebhookSender sender;

    @Before
    public void setUp() throws Exception {
        sender = new TestWebhookSender();
        SlackTestHelpers.installSeams(sender);
        SlackTestHelpers.addWebhookCredential("wh", "http://example.test/hook");
        SlackTestHelpers.config().setDefaultWebhookCredentialId("wh");
    }

    @Test
    public void matcherFlagsUnknownPlainAndBareVars() {
        assertThat(
                TemplateLint.rawFallbackNames("x=${DEPLOY_TARGET} y=$OTHER_VAR"),
                containsInAnyOrder("DEPLOY_TARGET", "OTHER_VAR"));
    }

    @Test
    public void matcherExcludesRecognizedMacroNames() {
        assertThat(TemplateLint.rawFallbackNames("${SLACK_GIT_BRANCH} ${SLACK_BUILD_URL}"), is(empty()));
    }

    @Test
    public void matcherSkipsDollarDollarAndNumericStarts() {
        assertThat(TemplateLint.rawFallbackNames("$${VAR} $5 $100 plain text"), is(empty()));
    }

    @Test
    public void sweepLogsOnceForRiskyConfiguredTemplate() {
        SlackTestHelpers.config().setDefaultSuccessTemplate("done ${DEPLOY_TARGET}");

        List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
        Handler handler = attach(TemplateMigrationSweep.class.getName(), records);
        try {
            TemplateMigrationSweep.warnOnRawFallbackTemplates();
        } finally {
            Logger.getLogger(TemplateMigrationSweep.class.getName()).removeHandler(handler);
        }

        long warnings = records.stream()
                .filter(r -> r.getLevel() == Level.WARNING)
                .filter(r -> r.getMessage().contains("DEPLOY_TARGET"))
                .count();
        assertEquals(1, warnings);
    }

    @Test
    public void renderRawFallbackWarnsOncePerTemplate() throws Exception {
        NotificationRule rule = SlackTestHelpers.rule("dedup", List.of("success"));
        rule.setSuccessTemplate("deploy=${DEPLOY_TARGET}"); // unregistered → raw fallback via macro-only expand()
        SlackTestHelpers.config().setRules(List.of(rule));

        List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
        Handler handler = attach(NotificationDispatcher.class.getName(), records);
        try {
            FreeStyleProject p = j.createFreeStyleProject("dedup");
            j.buildAndAssertSuccess(p);
            SlackTestHelpers.awaitDispatch();
            j.buildAndAssertSuccess(p);
            SlackTestHelpers.awaitDispatch();
        } finally {
            Logger.getLogger(NotificationDispatcher.class.getName()).removeHandler(handler);
        }

        long warnings = records.stream()
                .filter(r -> r.getLevel() == Level.WARNING)
                .filter(r -> r.getMessage() != null && r.getMessage().contains("unexpanded"))
                .count();
        // Two builds render the same raw-fallback template, but the WARNING is deduped per distinct template.
        assertEquals(1, warnings);
    }

    @Test
    public void matcherIgnoresUnterminatedDelimiter() {
        // Current behavior: an unterminated "${" is not treated as a token, so the sweep does not flag it.
        assertThat(TemplateLint.rawFallbackNames("x=${UNTERMINATED and more"), is(empty()));
    }

    @Test
    public void sweepWithCleanConfigLogsNothing() {
        SlackTestHelpers.config().setDefaultSuccessTemplate("all good ${SLACK_BUILD_URL}");

        List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
        Handler handler = attach(TemplateMigrationSweep.class.getName(), records);
        try {
            TemplateMigrationSweep.warnOnRawFallbackTemplates();
        } finally {
            Logger.getLogger(TemplateMigrationSweep.class.getName()).removeHandler(handler);
        }

        long warnings = records.stream().filter(r -> r.getLevel() == Level.WARNING).count();
        assertEquals(0, warnings);
    }

    @Test
    public void sweepScansPerRuleTemplates() {
        NotificationRule rule = SlackTestHelpers.rule("app", List.of("failure"));
        rule.setFailureTemplate("broke ${DEPLOY_TARGET}"); // per-rule override, unregistered name
        SlackTestHelpers.config().setRules(List.of(rule));

        List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
        Handler handler = attach(TemplateMigrationSweep.class.getName(), records);
        try {
            TemplateMigrationSweep.warnOnRawFallbackTemplates();
        } finally {
            Logger.getLogger(TemplateMigrationSweep.class.getName()).removeHandler(handler);
        }

        long warnings = records.stream()
                .filter(r -> r.getLevel() == Level.WARNING)
                .filter(r -> r.getMessage() != null && r.getMessage().contains("DEPLOY_TARGET"))
                .count();
        assertEquals(1, warnings);
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
}
