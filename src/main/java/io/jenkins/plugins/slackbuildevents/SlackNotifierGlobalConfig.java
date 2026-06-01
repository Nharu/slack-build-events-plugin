package io.jenkins.plugins.slackbuildevents;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Admin-only global configuration: default channel/webhook, the ordered rule list,
 * the 429 retry budget, and optional global default templates/color.
 *
 * <p>Every field is CASC-native (String/boolean/int/List) with getter/setter symmetry,
 * so JCasC export equals import and secrets are never serialized (only credential ids).
 */
@Extension
@Symbol("slackTemplatedNotifier")
public final class SlackNotifierGlobalConfig extends GlobalConfiguration {

    private static final int DEFAULT_MAX_RETRIES = 1;
    private static final int MAX_RETRIES_LIMIT = 5;

    private String defaultChannel;
    private String defaultWebhookCredentialId;
    private List<NotificationRule> rules = new ArrayList<>();
    private int maxRetriesOn429 = DEFAULT_MAX_RETRIES;

    private String defaultStartTemplate;
    private String defaultSuccessTemplate;
    private String defaultFailureTemplate;
    private String defaultUnstableTemplate;
    private String defaultAbortedTemplate;
    private String defaultNotBuiltTemplate;
    private String defaultColor;

    public SlackNotifierGlobalConfig() {
        load();
    }

    @CheckForNull
    static SlackNotifierGlobalConfig get() {
        return GlobalConfiguration.all().get(SlackNotifierGlobalConfig.class);
    }

    @CheckForNull
    public String getDefaultChannel() {
        return defaultChannel;
    }

    @DataBoundSetter
    public void setDefaultChannel(String defaultChannel) {
        this.defaultChannel = Util.fixEmptyAndTrim(defaultChannel);
        save();
    }

    @CheckForNull
    public String getDefaultWebhookCredentialId() {
        return defaultWebhookCredentialId;
    }

    @DataBoundSetter
    public void setDefaultWebhookCredentialId(String defaultWebhookCredentialId) {
        this.defaultWebhookCredentialId = Util.fixEmptyAndTrim(defaultWebhookCredentialId);
        save();
    }

    @NonNull
    public List<NotificationRule> getRules() {
        return Collections.unmodifiableList(rules);
    }

    @DataBoundSetter
    public void setRules(List<NotificationRule> rules) {
        this.rules = rules == null ? new ArrayList<>() : new ArrayList<>(rules);
        save();
    }

    public int getMaxRetriesOn429() {
        return maxRetriesOn429;
    }

    @DataBoundSetter
    public void setMaxRetriesOn429(int maxRetriesOn429) {
        this.maxRetriesOn429 = clamp(maxRetriesOn429);
        save();
    }

    @CheckForNull
    public String getDefaultStartTemplate() {
        return defaultStartTemplate;
    }

    @DataBoundSetter
    public void setDefaultStartTemplate(String defaultStartTemplate) {
        this.defaultStartTemplate = Util.fixEmpty(defaultStartTemplate);
        save();
    }

    @CheckForNull
    public String getDefaultSuccessTemplate() {
        return defaultSuccessTemplate;
    }

    @DataBoundSetter
    public void setDefaultSuccessTemplate(String defaultSuccessTemplate) {
        this.defaultSuccessTemplate = Util.fixEmpty(defaultSuccessTemplate);
        save();
    }

    @CheckForNull
    public String getDefaultFailureTemplate() {
        return defaultFailureTemplate;
    }

    @DataBoundSetter
    public void setDefaultFailureTemplate(String defaultFailureTemplate) {
        this.defaultFailureTemplate = Util.fixEmpty(defaultFailureTemplate);
        save();
    }

    @CheckForNull
    public String getDefaultUnstableTemplate() {
        return defaultUnstableTemplate;
    }

    @DataBoundSetter
    public void setDefaultUnstableTemplate(String defaultUnstableTemplate) {
        this.defaultUnstableTemplate = Util.fixEmpty(defaultUnstableTemplate);
        save();
    }

    @CheckForNull
    public String getDefaultAbortedTemplate() {
        return defaultAbortedTemplate;
    }

    @DataBoundSetter
    public void setDefaultAbortedTemplate(String defaultAbortedTemplate) {
        this.defaultAbortedTemplate = Util.fixEmpty(defaultAbortedTemplate);
        save();
    }

    @CheckForNull
    public String getDefaultNotBuiltTemplate() {
        return defaultNotBuiltTemplate;
    }

    @DataBoundSetter
    public void setDefaultNotBuiltTemplate(String defaultNotBuiltTemplate) {
        this.defaultNotBuiltTemplate = Util.fixEmpty(defaultNotBuiltTemplate);
        save();
    }

    @CheckForNull
    public String getDefaultColor() {
        return defaultColor;
    }

    @DataBoundSetter
    public void setDefaultColor(String defaultColor) {
        this.defaultColor = Util.fixEmptyAndTrim(defaultColor);
        save();
    }

    /** First rule (in list order) whose pattern full-matches the job, or {@code null}. */
    @CheckForNull
    NotificationRule firstMatch(@NonNull String jobFullName) {
        for (NotificationRule rule : rules) {
            if (rule.matches(jobFullName)) {
                return rule;
            }
        }
        return null;
    }

    /** Global-tier default template for an event, or {@code null} when unset. */
    @CheckForNull
    String defaultTemplateFor(@NonNull EventType event) {
        switch (event) {
            case START:
                return defaultStartTemplate;
            case SUCCESS:
                return defaultSuccessTemplate;
            case FAILURE:
                return defaultFailureTemplate;
            case UNSTABLE:
                return defaultUnstableTemplate;
            case ABORTED:
                return defaultAbortedTemplate;
            case NOT_BUILT:
                return defaultNotBuiltTemplate;
            default:
                return null;
        }
    }

    @POST
    public FormValidation doCheckMaxRetriesOn429(@QueryParameter String value) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String trimmed = Util.fixEmptyAndTrim(value);
        if (trimmed == null) {
            return FormValidation.ok();
        }
        int parsed;
        try {
            parsed = Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return FormValidation.error("Must be an integer between 0 and " + MAX_RETRIES_LIMIT + ".");
        }
        if (parsed < 0 || parsed > MAX_RETRIES_LIMIT) {
            return FormValidation.error("Must be between 0 (disabled) and " + MAX_RETRIES_LIMIT + ".");
        }
        return FormValidation.ok();
    }

    public ListBoxModel doFillDefaultWebhookCredentialIdItems(
            @QueryParameter String defaultWebhookCredentialId) {
        return WebhookCredentials.fillItems(defaultWebhookCredentialId);
    }

    private static int clamp(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, MAX_RETRIES_LIMIT);
    }
}
