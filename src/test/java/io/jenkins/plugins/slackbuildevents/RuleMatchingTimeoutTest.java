package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;

/**
 * ReDoS runtime guard: a pathological rule pattern aborts (fail-closed) instead of hanging the
 * dispatch thread, logs one WARNING per rule (pattern only, never the job name), and never blocks
 * later rules from matching.
 */
public class RuleMatchingTimeoutTest {

    private static final String PATHOLOGICAL = "(.*a){30}";

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Rule
    public LoggerRule logging = new LoggerRule();

    @Test(timeout = 5000)
    public void pathologicalPatternAbortsInsteadOfHanging() {
        NotificationRule rule = new NotificationRule(PATHOLOGICAL);
        // Unguarded this backtracks for ~15s; the step budget turns it into a fast no-match.
        assertFalse(rule.matches("a".repeat(90)));
    }

    @Test
    public void budgetPathLogsOneWarningPerRule() {
        logging.record(NotificationRule.class, Level.WARNING).capture(10);
        NotificationRule rule = new NotificationRule(PATHOLOGICAL);

        rule.matches("a".repeat(90));
        rule.matches("a".repeat(90)); // second budget hit on the same rule is deduped

        long warnings = logging.getRecords().stream()
                .filter(r -> render(r).contains(PATHOLOGICAL))
                .count();
        assertEquals(1, warnings);
        // The job full name (the input) must never be logged — only the pattern.
        for (LogRecord r : logging.getRecords()) {
            assertFalse(render(r).contains("a".repeat(90)));
        }
    }

    @Test(timeout = 5000)
    public void firstMatchContinuesPastPathologicalRule() {
        NotificationRule bad = SlackTestHelpers.rule(PATHOLOGICAL, List.of("start"));
        NotificationRule good = SlackTestHelpers.rule(".*svc", List.of("start"));
        SlackNotifierGlobalConfig config = SlackTestHelpers.config();
        config.setRules(List.of(bad, good));

        // The pathological rule aborts (→ no-match) for this input, so firstMatch must fall through
        // to the good rule rather than hang or stop at the first rule.
        NotificationRule matched = config.firstMatch("a".repeat(90) + "svc");
        assertNotNull(matched);
        assertEquals(".*svc", matched.getJobNamePattern());
    }

    private static String render(LogRecord r) {
        return r.getMessage() + " " + Arrays.toString(r.getParameters());
    }
}
