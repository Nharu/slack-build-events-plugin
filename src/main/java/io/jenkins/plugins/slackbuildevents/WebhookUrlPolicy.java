package io.jenkins.plugins.slackbuildevents;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.URI;
import java.util.List;

/**
 * Pure send-time webhook URL policy: optional https-only plus an optional host allowlist,
 * combined with AND (a URL must satisfy both to be permitted). Never surfaces the raw URL —
 * which may embed a secret token — in any return value or thrown message.
 */
final class WebhookUrlPolicy {

    private WebhookUrlPolicy() {}

    /**
     * @return {@code true} if the URL is permitted. A null or unparseable URL is rejected
     *     (fail-closed) without surfacing the parse exception (its message embeds the raw URL).
     *     {@code httpsOnly} requires an {@code https} scheme compared case-insensitively
     *     ({@link URI#getScheme()} preserves the input case). The host is delegated to
     *     {@link HostAllowlistMatcher} with the raw allowlist (normalization is single-point).
     */
    static boolean isAllowed(@CheckForNull String url, boolean httpsOnly, @NonNull List<String> allowlist) {
        if (url == null) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (httpsOnly && !"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        return HostAllowlistMatcher.matches(uri.getHost(), allowlist);
    }

    /** Scheme + host only (no path / query / userinfo), safe for logging without leaking secrets. */
    @NonNull
    static String describeSafely(@CheckForNull String url) {
        if (url != null) {
            try {
                URI uri = URI.create(url);
                String scheme = uri.getScheme();
                String host = uri.getHost();
                if (scheme != null && host != null) {
                    return scheme + "://" + host;
                }
            } catch (IllegalArgumentException ignored) {
                // fall through to the generic description
            }
        }
        return "(unparseable webhook URL)";
    }
}
