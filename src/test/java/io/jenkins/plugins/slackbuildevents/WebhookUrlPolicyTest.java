package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** Unit coverage for the pure send-time webhook URL policy and host suffix matcher. */
public class WebhookUrlPolicyTest {

    private static final List<String> SLACK = List.of("slack.com");

    // --- empty allowlist = unrestricted -------------------------------------------------

    @Test
    public void emptyAllowlistAllowsAnyHost() {
        assertTrue(WebhookUrlPolicy.isAllowed("http://10.0.0.1/x", false, List.of()));
        assertTrue(WebhookUrlPolicy.isAllowed("https://anything.example/x", false, List.of()));
    }

    // --- suffix boundary ----------------------------------------------------------------

    @Test
    public void suffixMatchesSubdomainButNotLookalike() {
        assertTrue(WebhookUrlPolicy.isAllowed("https://hooks.slack.com/services/T/B/x", false, SLACK));
        assertTrue(WebhookUrlPolicy.isAllowed("https://slack.com/x", false, SLACK));
        // classic evilslack.com bypass must not match
        assertFalse(WebhookUrlPolicy.isAllowed("https://evilslack.com/x", false, SLACK));
        assertFalse(WebhookUrlPolicy.isAllowed("https://slack.com.evil.example/x", false, SLACK));
    }

    // --- userinfo bypass ----------------------------------------------------------------

    @Test
    public void userinfoDoesNotSpoofHost() {
        // getHost() strips userinfo → real host is evil.example
        assertFalse(WebhookUrlPolicy.isAllowed("https://hooks.slack.com@evil.example/x", false, SLACK));
    }

    // --- case + trailing dot normalization ----------------------------------------------

    @Test
    public void hostComparisonIsCaseAndTrailingDotInsensitive() {
        assertTrue(WebhookUrlPolicy.isAllowed("https://Hooks.Slack.COM./x", false, SLACK));
        assertTrue(WebhookUrlPolicy.isAllowed("https://hooks.slack.com/x", false, List.of("SLACK.COM")));
        assertTrue(WebhookUrlPolicy.isAllowed("https://hooks.slack.com/x", false, List.of("slack.com.")));
    }

    // --- null / unparseable host = fail-closed ------------------------------------------

    @Test
    public void nullHostFailsClosed() {
        // %41 authority yields a non-null authority but null host
        assertFalse(WebhookUrlPolicy.isAllowed("http://ex%41mple.com/x", false, SLACK));
    }

    @Test
    public void unparseableUrlFailsClosed() {
        assertFalse(WebhookUrlPolicy.isAllowed("ht!tp://sekret@internal/x", false, List.of()));
        assertFalse(WebhookUrlPolicy.isAllowed(null, false, List.of()));
    }

    // --- httpsOnly ----------------------------------------------------------------------

    @Test
    public void httpsOnlyBlocksHttpAndAcceptsUppercaseScheme() {
        assertFalse(WebhookUrlPolicy.isAllowed("http://hooks.slack.com/x", true, SLACK));
        assertTrue(WebhookUrlPolicy.isAllowed("https://hooks.slack.com/x", true, SLACK));
        // getScheme() preserves case; the comparison must be case-insensitive
        assertTrue(WebhookUrlPolicy.isAllowed("HTTPS://hooks.slack.com/x", true, SLACK));
    }

    @Test
    public void httpsOnlyIsAndCombinedWithAllowlist() {
        // https but host not in allowlist → blocked
        assertFalse(WebhookUrlPolicy.isAllowed("https://evil.example/x", true, SLACK));
    }

    // --- IDN exception entry is skipped, others still consulted --------------------------

    @Test
    public void malformedAllowlistEntryIsSkippedNotFatal() {
        List<String> withBadEntry = List.of(".slack.com", "hooks.slack.com");
        // the empty-label entry ".slack.com" throws in IDN → skipped; the good entry still matches
        assertTrue(WebhookUrlPolicy.isAllowed("https://hooks.slack.com/x", false, withBadEntry));
    }

    // --- IPv6 bracket literal must be entered in bracket form ----------------------------

    @Test
    public void ipv6BracketLiteralMatchesWhenListedInBracketForm() {
        assertTrue(HostAllowlistMatcher.matches("[::1]", List.of("[::1]")));
        assertFalse(HostAllowlistMatcher.matches("[::1]", List.of("::1")));
    }

    // --- matcher direct: empty entry never matches, null host fail-closed ---------------

    @Test
    public void matcherEdgeCases() {
        assertFalse(HostAllowlistMatcher.matches("hooks.slack.com", List.of("")));
        assertFalse(HostAllowlistMatcher.matches(null, SLACK));
        assertTrue(HostAllowlistMatcher.matches(null, List.of()));
    }

    // --- describeSafely never leaks path / userinfo -------------------------------------

    @Test
    public void describeSafelyExposesOnlySchemeAndHost() {
        assertEquals("https://hooks.slack.com", WebhookUrlPolicy.describeSafely("https://hooks.slack.com/T/B/SECRET"));
        assertEquals("(unparseable webhook URL)", WebhookUrlPolicy.describeSafely("ht!tp://x"));
    }
}
