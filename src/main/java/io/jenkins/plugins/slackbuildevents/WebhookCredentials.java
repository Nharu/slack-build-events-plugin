package io.jenkins.plugins.slackbuildevents;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.security.ACL;
import hudson.util.ListBoxModel;
import java.util.Collections;
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
}
