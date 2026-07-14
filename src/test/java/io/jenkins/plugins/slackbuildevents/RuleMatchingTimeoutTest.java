package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import jenkins.model.Jenkins;
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

    @Test(timeout = 5000)
    public void lengthGuardSkipsOverlongInputWithoutStackOverflow() {
        // Each of these would overflow the engine's recursion (StackOverflowError) on a long all-'a'
        // input if it reached the matcher; the input length guard (> MAX_MATCH_INPUT_LENGTH) short-
        // circuits to no-match first, so firstMatch completes quickly instead of aborting mid-loop.
        for (String soe : List.of("(a|a)*", "(a?)*", "(a|aa)+")) {
            NotificationRule bad = SlackTestHelpers.rule(soe, List.of("start"));
            SlackNotifierGlobalConfig config = SlackTestHelpers.config();
            config.setRules(List.of(bad));
            assertNull("overlong input must be no-match for " + soe, config.firstMatch("a".repeat(20000)));
        }
    }

    @Test
    public void lengthGuardLogsPrefixNotFullNameAndDedups() {
        logging.record(NotificationRule.class, Level.WARNING).capture(10);
        NotificationRule rule = new NotificationRule("team/.*");
        String longName = "team/" + "x".repeat(2000); // length 2005 > MAX_MATCH_INPUT_LENGTH

        assertFalse(rule.matches(longName));
        assertFalse(rule.matches(longName)); // second overlong hit on the same rule is deduped

        assertEquals(1, logging.getRecords().size());
        String rendered = render(logging.getRecords().get(0));
        // Path isolation: the skip came from the length guard, not the step-budget / stack-overflow catch.
        assertTrue("skip is from the length guard", rendered.contains("exceeds the match input limit"));
        assertFalse("not the step-budget path", rendered.contains("match step budget"));
        assertFalse("not the stack-overflow path", rendered.contains("overflowed the stack"));
        assertTrue("full length is reported", rendered.contains("2005"));
        assertFalse("the full job name must never be logged", rendered.contains(longName));
    }

    @Test
    public void lengthGuardAndCatchSiteDedupIndependently() {
        logging.record(NotificationRule.class, Level.WARNING).capture(10);
        NotificationRule rule = new NotificationRule(".*a");

        rule.matches("a".repeat(2000)); // length guard fires (input > MAX_MATCH_INPUT_LENGTH)
        rule.matches("aaaaaaaaaa", 3L); // same rule: short input, tiny budget → matcher catch site fires

        // Two distinct failure modes on one rule: the length-guard warning must not silence the
        // catch-site warning (separate per-site dedup flags).
        long lengthWarnings = logging.getRecords().stream()
                .filter(r -> render(r).contains("exceeds the match input limit"))
                .count();
        long catchWarnings = logging.getRecords().stream()
                .filter(r -> render(r).contains("match step budget"))
                .count();
        assertEquals(1, lengthWarnings);
        assertEquals(1, catchWarnings);
    }

    @Test
    public void dedupFlagResetsAcrossXStreamRoundTrip() {
        logging.record(NotificationRule.class, Level.WARNING).capture(10);
        NotificationRule rule = new NotificationRule(".*a");

        // Tiny budget forces the step-budget WARNING path on a short (length-guard-safe) input.
        rule.matches("aaaaaaaaaa", 3L);
        rule.matches("aaaaaaaaaa", 3L); // deduped on the same instance → still one WARNING
        assertEquals(1, warningCount());

        // XStream unmarshal resets the transient dedup flag → a fresh WARNING after reload.
        NotificationRule reloaded = (NotificationRule) Jenkins.XSTREAM2.fromXML(Jenkins.XSTREAM2.toXML(rule));
        reloaded.matches("aaaaaaaaaa", 3L);
        assertEquals(2, warningCount());
    }

    private long warningCount() {
        return logging.getRecords().stream().filter(r -> r.getLevel() == Level.WARNING).count();
    }

    private static String render(LogRecord r) {
        return r.getMessage() + " " + Arrays.toString(r.getParameters());
    }
}
