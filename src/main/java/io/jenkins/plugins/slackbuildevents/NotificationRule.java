package io.jenkins.plugins.slackbuildevents;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * One ordered allowlist rule: a job full-name regex plus per-event toggles and
 * optional channel / webhook / per-event template overrides. The first matching rule
 * (in list order) governs all of a job's events.
 *
 * <p>Field names are part of the JCasC serialization contract (getter/setter symmetry).
 */
public class NotificationRule extends AbstractDescribableImpl<NotificationRule> {

    private static final Logger LOGGER = Logger.getLogger(NotificationRule.class.getName());

    /** Backtracking guard: max charAt reads in a single match before it is aborted (ReDoS). */
    private static final long MATCH_STEP_BUDGET = 1_000_000L;

    /**
     * Upper bound on the job full name length fed to the matcher. A pathological pattern whose engine
     * recursion overflows the stack (e.g. {@code (a|a)*}) can throw {@link StackOverflowError} after
     * only a few thousand reads — below the step budget — so a length guard deterministically prevents
     * reaching that depth. Well above any legitimate (deep folder) job name.
     */
    private static final int MAX_MATCH_INPUT_LENGTH = 1024;

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
    // Shared dedup flag for all three match-guard WARNING paths (step budget, stack overflow, input
    // length): one WARNING per rule. Plain (non-atomic) transient: reset to false on XStream
    // deserialization, so no path can NPE the way a null AtomicBoolean reference would; a benign
    // duplicate log under a race is accepted. Mirrors the compileAttempted transient convention above.
    private transient boolean matchGuardWarned;

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

    /**
     * Full-match of this rule's (cached, precompiled) pattern against the job full name, bounded by
     * a backtracking step budget so a pathological pattern cannot pin the dispatch thread.
     */
    boolean matches(@NonNull String jobFullName) {
        return matches(jobFullName, MATCH_STEP_BUDGET);
    }

    /** Test seam: {@link #matches(String)} with an explicit step budget. */
    boolean matches(@NonNull String jobFullName, long stepBudget) {
        // Input length guard (before the matcher): deterministically prevents a stack-overflowing
        // pattern from reaching engine-recursion depth, which can precede the step budget. Log the
        // length and a bounded prefix only — never the full name — so a legitimately long (deep
        // folder) job that keeps hitting this guard is still identifiable without log amplification.
        if (jobFullName.length() > MAX_MATCH_INPUT_LENGTH) {
            if (!matchGuardWarned) {
                matchGuardWarned = true;
                LOGGER.log(
                        Level.WARNING,
                        "Slack notification rule skipped: job full name length {0} exceeds the match input "
                                + "limit {1}; name prefix: {2}",
                        new Object[] {jobFullName.length(), MAX_MATCH_INPUT_LENGTH, prefix(jobFullName)});
            }
            return false;
        }
        Pattern pattern = pattern();
        if (pattern == null) {
            return false;
        }
        try {
            return pattern.matcher(new BoundedCharSequence(jobFullName, stepBudget)).matches();
        } catch (MatchBudgetExceededException | StackOverflowError e) {
            // Fail-closed: a runaway match (step budget exceeded) or a stack-overflowing engine
            // recursion is treated as no-match — the same branch as an invalid regex — so firstMatch
            // keeps consulting the remaining rules instead of the loop being aborted mid-way.
            if (!matchGuardWarned) {
                matchGuardWarned = true;
                LOGGER.log(
                        Level.WARNING,
                        e instanceof StackOverflowError
                                ? "Slack notification rule pattern overflowed the stack and was skipped "
                                        + "(possible ReDoS); pattern: {0}"
                                : "Slack notification rule pattern exceeded its match step budget and was "
                                        + "skipped (possible ReDoS); pattern: {0}",
                        jobNamePattern);
            }
            return false;
        }
    }

    /** Bounded, safe-to-log prefix of a job full name (never the whole name). */
    @NonNull
    private static String prefix(@NonNull String jobFullName) {
        return jobFullName.substring(0, Math.min(128, jobFullName.length()));
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

    /**
     * Wraps the job full name and counts {@code charAt} calls, throwing once a match consumes more
     * than {@code budget} reads. Backtracking re-reads positions, so the read count is a monotone
     * proxy for backtracking work across every pathological pattern family.
     */
    private static final class BoundedCharSequence implements CharSequence {
        private final String value;
        private final long budget;
        private long reads;

        BoundedCharSequence(String value, long budget) {
            this.value = value;
            this.budget = budget;
        }

        @Override
        public int length() {
            return value.length();
        }

        @Override
        public char charAt(int index) {
            if (++reads > budget) {
                throw new MatchBudgetExceededException();
            }
            return value.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            // matches() never calls this. Fail loudly so a future group-extraction use that slips in
            // is caught at development time rather than silently bypassing the step budget (uncounted).
            throw new UnsupportedOperationException("BoundedCharSequence.subSequence is not supported");
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /** Stackless signal that a match exceeded its step budget; never escapes {@link #matches(String)}. */
    private static final class MatchBudgetExceededException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        MatchBudgetExceededException() {
            super(null, null, false, false);
        }
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

        /** Config-time self-check probe: bounded like the runtime guard but tighter, over a fixed input. */
        private static final long PROBE_STEP_BUDGET = 200_000L;

        private static final String PROBE_INPUT = "a".repeat(48) + "!";

        /** Tier-1 static hints: backreference, a group-quantifier immediately re-quantified, big counted repeat. */
        private static final Pattern BACKREF = Pattern.compile(".*\\\\[1-9].*", Pattern.DOTALL);
        private static final Pattern NESTED_QUANTIFIER = Pattern.compile(".*\\([^()]*[+*][^()]*\\)[+*].*", Pattern.DOTALL);
        private static final Pattern LARGE_COUNTED = Pattern.compile(".*\\{\\s*\\d{3,}.*", Pattern.DOTALL);
        /** Stack-overflow shape: an alternation/optional/star inside a group that is itself repeated. */
        private static final Pattern SOE_SHAPE =
                Pattern.compile(".*\\([^()]*[|?*+][^()]*\\)\\s*[*+{].*", Pattern.DOTALL);

        /** SOE probe: small fixed stack so a deep-recursion pattern overflows regardless of host -Xss. */
        private static final int SOE_PROBE_STACK_BYTES = 256 * 1024;

        private static final String SOE_PROBE_INPUT = "a".repeat(12000);

        private static final long SOE_PROBE_JOIN_MILLIS = 5000L;

        @Override
        @NonNull
        public String getDisplayName() {
            return "Slack notification rule";
        }

        @POST
        public FormValidation doCheckJobNamePattern(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (Util.fixEmptyAndTrim(value) == null) {
                return FormValidation.error("A job name pattern is required.");
            }
            Pattern compiled;
            try {
                compiled = Pattern.compile(value);
            } catch (PatternSyntaxException e) {
                return FormValidation.error("Invalid regular expression: " + e.getMessage());
            }
            // Tier-2 (authoritative): run the real engine against a synthetic breaker under a small
            // budget; catastrophic backtracking aborts and is surfaced as a warning (version-accurate).
            try {
                compiled.matcher(new BoundedCharSequence(PROBE_INPUT, PROBE_STEP_BUDGET)).matches();
            } catch (MatchBudgetExceededException e) {
                return FormValidation.warning(
                        "This pattern back-tracks catastrophically on a synthetic input and would be "
                                + "aborted at match time (possible ReDoS); consider simplifying it.");
            }
            // SOE probe (authoritative for stack-overflowing shapes): a small fixed-stack thread makes
            // a deep-recursion pattern overflow deterministically, independent of the host -Xss.
            if (probeStackOverflow(compiled)) {
                return FormValidation.warning(
                        "This pattern overflows the regex engine's stack on a long input and would be "
                                + "skipped at match time (possible ReDoS); consider simplifying it.");
            }
            // Tier-1 (static hint): shapes commonly associated with pathological backtracking or overflow.
            if (BACKREF.matcher(value).matches()
                    || NESTED_QUANTIFIER.matcher(value).matches()
                    || LARGE_COUNTED.matcher(value).matches()
                    || SOE_SHAPE.matcher(value).matches()) {
                return FormValidation.warning(
                        "This pattern contains a shape (nested quantifier, large counted repetition, or "
                                + "backreference) that can back-track expensively; matching is step-limited "
                                + "at runtime, but consider simplifying it.");
            }
            return FormValidation.ok(
                    "Full-matched against the job full name; include folder separators '/' "
                            + "explicitly (e.g. 'team/.*'). Matching is step-limited at runtime, so a "
                            + "pathological pattern is skipped rather than hanging the notification thread.");
        }

        /**
         * Runs the pattern on a 256KB-stack thread against a long input, catching {@link
         * StackOverflowError} only (a step-budget abort falls through as "not an overflow"). Returns
         * {@code true} if it overflowed. A probe that does not finish within the join timeout is
         * treated as inconclusive ({@code false}) — warn-only, so a slow probe never blocks saving and
         * the runtime guard remains the backstop.
         */
        private static boolean probeStackOverflow(Pattern compiled) {
            AtomicBoolean overflowed = new AtomicBoolean(false);
            Thread t = new Thread(
                    null,
                    () -> {
                        try {
                            compiled.matcher(new BoundedCharSequence(SOE_PROBE_INPUT, MATCH_STEP_BUDGET))
                                    .matches();
                        } catch (StackOverflowError e) {
                            overflowed.set(true);
                        } catch (MatchBudgetExceededException e) {
                            // Budget hit first, not a stack overflow → inconclusive for SOE.
                        }
                    },
                    "slack-redos-soe-probe",
                    SOE_PROBE_STACK_BYTES);
            t.setDaemon(true);
            t.start();
            try {
                t.join(SOE_PROBE_JOIN_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return overflowed.get();
        }

        public ListBoxModel doFillWebhookCredentialIdItems(@QueryParameter String webhookCredentialId) {
            return WebhookCredentials.fillItems(webhookCredentialId);
        }

        @POST
        public FormValidation doCheckWebhookCredentialId(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            return WebhookCredentials.checkUrlPolicy(value);
        }
    }
}
