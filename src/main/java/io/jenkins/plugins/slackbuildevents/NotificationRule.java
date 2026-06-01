package io.jenkins.plugins.slackbuildevents;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

/**
 * One ordered allowlist rule: a job full-name regex plus per-event toggles and
 * optional channel / webhook / per-event template overrides. The first matching rule
 * (in list order) governs all of a job's events.
 *
 * <p>Field names are part of the JCasC serialization contract (getter/setter symmetry).
 */
public class NotificationRule extends AbstractDescribableImpl<NotificationRule> {

    private final String jobNamePattern;
    private String channel;
    private String webhookCredentialId;

    private boolean notifyStart;
    private boolean notifySuccess;
    private boolean notifyFailure;
    private boolean notifyUnstable;
    private boolean notifyAborted;
    private boolean notifyNotBuilt;

    private String startTemplate;
    private String successTemplate;
    private String failureTemplate;
    private String unstableTemplate;
    private String abortedTemplate;
    private String notBuiltTemplate;

    private transient Pattern compiled;
    private transient boolean compileAttempted;

    @DataBoundConstructor
    public NotificationRule(String jobNamePattern) {
        this.jobNamePattern = jobNamePattern;
    }

    public String getJobNamePattern() {
        return jobNamePattern;
    }

    @CheckForNull
    public String getChannel() {
        return channel;
    }

    @DataBoundSetter
    public void setChannel(String channel) {
        this.channel = Util.fixEmptyAndTrim(channel);
    }

    @CheckForNull
    public String getWebhookCredentialId() {
        return webhookCredentialId;
    }

    @DataBoundSetter
    public void setWebhookCredentialId(String webhookCredentialId) {
        this.webhookCredentialId = Util.fixEmptyAndTrim(webhookCredentialId);
    }

    public boolean isNotifyStart() {
        return notifyStart;
    }

    @DataBoundSetter
    public void setNotifyStart(boolean notifyStart) {
        this.notifyStart = notifyStart;
    }

    public boolean isNotifySuccess() {
        return notifySuccess;
    }

    @DataBoundSetter
    public void setNotifySuccess(boolean notifySuccess) {
        this.notifySuccess = notifySuccess;
    }

    public boolean isNotifyFailure() {
        return notifyFailure;
    }

    @DataBoundSetter
    public void setNotifyFailure(boolean notifyFailure) {
        this.notifyFailure = notifyFailure;
    }

    public boolean isNotifyUnstable() {
        return notifyUnstable;
    }

    @DataBoundSetter
    public void setNotifyUnstable(boolean notifyUnstable) {
        this.notifyUnstable = notifyUnstable;
    }

    public boolean isNotifyAborted() {
        return notifyAborted;
    }

    @DataBoundSetter
    public void setNotifyAborted(boolean notifyAborted) {
        this.notifyAborted = notifyAborted;
    }

    public boolean isNotifyNotBuilt() {
        return notifyNotBuilt;
    }

    @DataBoundSetter
    public void setNotifyNotBuilt(boolean notifyNotBuilt) {
        this.notifyNotBuilt = notifyNotBuilt;
    }

    @CheckForNull
    public String getStartTemplate() {
        return startTemplate;
    }

    @DataBoundSetter
    public void setStartTemplate(String startTemplate) {
        this.startTemplate = Util.fixEmpty(startTemplate);
    }

    @CheckForNull
    public String getSuccessTemplate() {
        return successTemplate;
    }

    @DataBoundSetter
    public void setSuccessTemplate(String successTemplate) {
        this.successTemplate = Util.fixEmpty(successTemplate);
    }

    @CheckForNull
    public String getFailureTemplate() {
        return failureTemplate;
    }

    @DataBoundSetter
    public void setFailureTemplate(String failureTemplate) {
        this.failureTemplate = Util.fixEmpty(failureTemplate);
    }

    @CheckForNull
    public String getUnstableTemplate() {
        return unstableTemplate;
    }

    @DataBoundSetter
    public void setUnstableTemplate(String unstableTemplate) {
        this.unstableTemplate = Util.fixEmpty(unstableTemplate);
    }

    @CheckForNull
    public String getAbortedTemplate() {
        return abortedTemplate;
    }

    @DataBoundSetter
    public void setAbortedTemplate(String abortedTemplate) {
        this.abortedTemplate = Util.fixEmpty(abortedTemplate);
    }

    @CheckForNull
    public String getNotBuiltTemplate() {
        return notBuiltTemplate;
    }

    @DataBoundSetter
    public void setNotBuiltTemplate(String notBuiltTemplate) {
        this.notBuiltTemplate = Util.fixEmpty(notBuiltTemplate);
    }

    /** Full-match of this rule's (cached, precompiled) pattern against the job full name. */
    boolean matches(@NonNull String jobFullName) {
        Pattern pattern = pattern();
        return pattern != null && pattern.matcher(jobFullName).matches();
    }

    private synchronized Pattern pattern() {
        if (!compileAttempted) {
            compileAttempted = true;
            try {
                compiled = jobNamePattern == null ? null : Pattern.compile(jobNamePattern);
            } catch (PatternSyntaxException e) {
                compiled = null;
            }
        }
        return compiled;
    }

    boolean isEnabledFor(@NonNull EventType event) {
        switch (event) {
            case START:
                return notifyStart;
            case SUCCESS:
                return notifySuccess;
            case FAILURE:
                return notifyFailure;
            case UNSTABLE:
                return notifyUnstable;
            case ABORTED:
                return notifyAborted;
            case NOT_BUILT:
                return notifyNotBuilt;
            default:
                return false;
        }
    }

    @CheckForNull
    String templateFor(@NonNull EventType event) {
        switch (event) {
            case START:
                return startTemplate;
            case SUCCESS:
                return successTemplate;
            case FAILURE:
                return failureTemplate;
            case UNSTABLE:
                return unstableTemplate;
            case ABORTED:
                return abortedTemplate;
            case NOT_BUILT:
                return notBuiltTemplate;
            default:
                return null;
        }
    }

    @Extension
    @Symbol("rule")
    public static class DescriptorImpl extends Descriptor<NotificationRule> {

        @Override
        @NonNull
        public String getDisplayName() {
            return "Slack notification rule";
        }

        public FormValidation doCheckJobNamePattern(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (Util.fixEmptyAndTrim(value) == null) {
                return FormValidation.error("A job name pattern is required.");
            }
            try {
                Pattern.compile(value);
            } catch (PatternSyntaxException e) {
                return FormValidation.error("Invalid regular expression: " + e.getMessage());
            }
            return FormValidation.ok(
                    "Full-matched against the job full name; include folder separators '/' "
                            + "explicitly (e.g. 'team/.*').");
        }

        public ListBoxModel doFillWebhookCredentialIdItems(@QueryParameter String webhookCredentialId) {
            return WebhookCredentials.fillItems(webhookCredentialId);
        }
    }
}
