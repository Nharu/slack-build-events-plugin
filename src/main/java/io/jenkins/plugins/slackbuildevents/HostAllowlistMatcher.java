package io.jenkins.plugins.slackbuildevents;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.IDN;
import java.util.List;
import java.util.Locale;

/**
 * Pure host-vs-allowlist suffix matcher for the webhook SSRF control. Both the host and each
 * allowlist entry are normalized (lowercase, one trailing dot stripped, IDN A-label) only at
 * compare time, so the stored allowlist is preserved verbatim.
 */
final class HostAllowlistMatcher {

    private HostAllowlistMatcher() {}

    /**
     * @return {@code true} if {@code allowlist} is empty (unrestricted) or {@code host}
     *     suffix-matches a normalized entry ({@code host == entry} or {@code host} ends with
     *     {@code "." + entry}); {@code false} if {@code host} is null / unparseable (fail-closed)
     *     or matches no entry. An entry whose normalization throws is skipped, and the remaining
     *     entries are still consulted; no normalization exception ever escapes this method.
     */
    static boolean matches(@CheckForNull String host, @NonNull List<String> allowlist) {
        if (allowlist.isEmpty()) {
            return true;
        }
        String normalizedHost = normalize(host);
        if (normalizedHost == null) {
            return false;
        }
        for (String entry : allowlist) {
            String normalizedEntry = normalize(entry);
            if (normalizedEntry == null) {
                // Entry normalization threw (empty-label / overlong label) → skip this entry only.
                continue;
            }
            if (normalizedHost.equals(normalizedEntry) || normalizedHost.endsWith("." + normalizedEntry)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lowercase (ROOT), strip a single trailing dot, then IDN A-label. Returns {@code null} if the
     * input is null or {@code IDN.toASCII} rejects it (empty label / overlong label); the empty
     * string and IPv6 bracket / IP literals pass through unchanged and are handled by the caller's
     * suffix rule.
     */
    @CheckForNull
    private static String normalize(@CheckForNull String value) {
        if (value == null) {
            return null;
        }
        String s = value.toLowerCase(Locale.ROOT);
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        try {
            return IDN.toASCII(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
