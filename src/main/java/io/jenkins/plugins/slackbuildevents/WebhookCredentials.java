package io.jenkins.plugins.slackbuildevents;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.util.Collections;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

/**
 * Shared credential drop-down population for the global config and rule descriptors.
 *
 * <p>Listed from the <b>root</b> ({@code Jenkins.get()}) context so {@code SYSTEM}-scope
 * webhook credentials are visible (they are hidden in job contexts). Gated on
 * {@link Jenkins#ADMINISTER}; only credential ids are exposed, never secrets.
 */
final class WebhookCredentials {

    private WebhookCredentials() {}

    @NonNull
    static ListBoxModel fillItems(@CheckForNull String currentValue) {
        Jenkins jenkins = Jenkins.get();
        StandardListBoxModel model = new StandardListBoxModel();
        if (!jenkins.hasPermission(Jenkins.ADMINISTER)) {
            return model.includeCurrentValue(currentValue == null ? "" : currentValue);
        }
        return model.includeEmptyValue()
                .includeMatchingAs(
                        ACL.SYSTEM2,
                        jenkins,
                        StringCredentials.class,
                        Collections.<DomainRequirement>emptyList(),
                        CredentialsMatchers.always())
                .includeCurrentValue(currentValue == null ? "" : currentValue);
    }

    /** Resolves the plaintext webhook URL behind a credential id, or {@code null} if unavailable. */
    @CheckForNull
    static String resolveUrl(@CheckForNull String credentialId) {
        if (credentialId == null || credentialId.isEmpty()) {
            return null;
        }
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return null;
        }
        List<StringCredentials> all = CredentialsProvider.lookupCredentialsInItemGroup(
                StringCredentials.class, jenkins, ACL.SYSTEM2, Collections.<DomainRequirement>emptyList());
        StringCredentials match = CredentialsMatchers.firstOrNull(all, CredentialsMatchers.withId(credentialId));
        return match == null ? null : match.getSecret().getPlainText();
    }

    /**
     * Best-effort admin-facing check that the webhook URL behind {@code credentialId} would pass
     * the currently-stored SSRF policy (host allowlist + https-only). Warn-only — the authoritative
     * guard is at send time — and never surfaces the raw URL or a parse exception message.
     */
    @NonNull
    static FormValidation checkUrlPolicy(@CheckForNull String credentialId) {
        String url = resolveUrl(credentialId);
        if (url == null) {
            return FormValidation.ok();
        }
        SlackNotifierGlobalConfig config = SlackNotifierGlobalConfig.get();
        if (config == null
                || WebhookUrlPolicy.isAllowed(url, config.isHttpsOnly(), config.getWebhookHostAllowlist())) {
            return FormValidation.ok();
        }
        return FormValidation.warning(
                "This webhook URL would be blocked at send time by the configured host allowlist / "
                        + "https-only policy.");
    }
}
