package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertEquals;

import hudson.util.FormValidation;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * ReDoS-aware {@code doCheckJobNamePattern}: the Tier-2 empirical probe catches catastrophic
 * patterns, the Tier-1 static scan hints at risky shapes, both warn-only, and the pre-existing
 * required / invalid-regex ERROR behavior is preserved.
 */
public class JobNamePatternDoCheckTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private NotificationRule.DescriptorImpl descriptor;

    @Before
    public void setUp() {
        descriptor = j.jenkins.getDescriptorByType(NotificationRule.DescriptorImpl.class);
    }

    @Test
    public void tier2WarnsOnCatastrophicPattern() {
        assertEquals(FormValidation.Kind.WARNING, descriptor.doCheckJobNamePattern("(.*a){30}").kind);
    }

    @Test
    public void tier1WarnsOnLargeCountedRepetition() {
        // Safe at runtime against the fixed probe input, so Tier-2 stays silent — Tier-1 flags it.
        assertEquals(FormValidation.Kind.WARNING, descriptor.doCheckJobNamePattern("a{500}").kind);
    }

    @Test
    public void tier1WarnsOnBackreference() {
        assertEquals(FormValidation.Kind.WARNING, descriptor.doCheckJobNamePattern("(.)\\1").kind);
    }

    @Test
    public void okOnNormalPattern() {
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckJobNamePattern("team/.*").kind);
    }

    @Test
    public void errorOnInvalidRegexIsPreserved() {
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckJobNamePattern("(").kind);
    }

    @Test
    public void errorOnEmptyIsPreserved() {
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckJobNamePattern("").kind);
    }

    @Test
    public void soeShapePatternsWarn() {
        // alternation/optional-under-star: flagged by the SOE_SHAPE static hint and the 256KB probe.
        assertEquals(FormValidation.Kind.WARNING, descriptor.doCheckJobNamePattern("(a|a)*").kind);
        assertEquals(FormValidation.Kind.WARNING, descriptor.doCheckJobNamePattern("(a?)*").kind);
        assertEquals(FormValidation.Kind.WARNING, descriptor.doCheckJobNamePattern("(a|aa)+").kind);
    }

    @Test
    public void nestedParenOverflowIsCaughtByProbe() {
        // SOE_SHAPE misses nested parens, but the probe overflows → WARNING (probe/static complement).
        assertEquals(FormValidation.Kind.WARNING, descriptor.doCheckJobNamePattern("((a|a))*").kind);
    }

    @Test
    public void benignPatternsProduceNoFalsePositive() {
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckJobNamePattern(".*").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckJobNamePattern("team/.*").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckJobNamePattern("(https?|ftp)://.*").kind);
    }
}
