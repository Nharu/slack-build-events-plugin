package io.jenkins.plugins.slackbuildevents;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controller-global listener that fires Slack notifications on build start and
 * completion. Auto-registered once per plugin by Jenkins, so no idempotent
 * registration / de-duplication guard is needed.
 *
 * <p>Matching and context capture happen on the (cheap) event thread; rendering and
 * the HTTP POST are handed to {@link NotificationDispatcher} so Slack latency never
 * blocks a build.
 */
@Extension
public class SlackNotifyRunListener extends RunListener<Run<?, ?>> {

    private static final Logger LOGGER = Logger.getLogger(SlackNotifyRunListener.class.getName());

    /**
     * Child runs that must be skipped (only their parent aggregate build is handled).
     * Matched by class name so that absence of matrix-project / maven-plugin never
     * triggers class loading on a controller without them.
     */
    private static final Set<String> CHILD_RUN_CLASSES =
            Set.of("hudson.matrix.MatrixRun", "hudson.maven.MavenBuild");

    @Override
    public void onStarted(Run<?, ?> run, TaskListener listener) {
        // Start event: pass the live listener (log stream is open).
        handle(run, EventType.START, listener);
    }

    @Override
    public void onCompleted(Run<?, ?> run, TaskListener listener) {
        Result result = run.getResult();
        if (result == null) {
            // Abnormal path: no result set. Skip the completion message (safe side:
            // no message is safer than a wrong one).
            return;
        }
        EventType event = EventType.fromResult(result);
        if (event == null) {
            return;
        }
        // Completion fires during Run.execute() teardown with the log stream closing;
        // pass TaskListener.NULL to avoid writing to a dead stream.
        handle(run, event, TaskListener.NULL);
    }

    private void handle(@NonNull Run<?, ?> run, @NonNull EventType event, @NonNull TaskListener listener) {
        try {
            if (CHILD_RUN_CLASSES.contains(run.getClass().getName())) {
                return;
            }
            String fullName = run.getParent().getFullName();

            SlackNotifierGlobalConfig config = SlackNotifierGlobalConfig.get();
            if (config == null) {
                return;
            }
            NotificationRule rule = config.firstMatch(fullName);
            if (rule == null || !rule.isEnabledFor(event)) {
                // 0 matches or event toggle off → opt-in allowlist fires nothing.
                return;
            }

            String webhookCredentialId =
                    firstNonEmpty(rule.getWebhookCredentialId(), config.getDefaultWebhookCredentialId());
            if (webhookCredentialId == null) {
                // No webhook configured anywhere → silent no-op.
                return;
            }
            String channel = firstNonEmpty(rule.getChannel(), config.getDefaultChannel());
            String template = resolveTemplate(rule, config, event);
            String defaultColor = config.getDefaultColor();
            String color = (defaultColor != null && !defaultColor.isEmpty()) ? defaultColor : event.defaultColor();

            NotificationContext context =
                    new NotificationContext(run, listener, channel, webhookCredentialId, template, color);
            NotificationDispatcher.get().dispatch(context);
        } catch (RuntimeException e) {
            // Belt-and-suspenders: a listener must never break a build.
            LOGGER.log(Level.WARNING, "Slack notification listener failed", e);
        }
    }

    @NonNull
    private static String resolveTemplate(
            @NonNull NotificationRule rule, @NonNull SlackNotifierGlobalConfig config, @NonNull EventType event) {
        String fromRule = rule.templateFor(event);
        if (fromRule != null && !fromRule.isEmpty()) {
            return fromRule;
        }
        String fromGlobal = config.defaultTemplateFor(event);
        if (fromGlobal != null && !fromGlobal.isEmpty()) {
            return fromGlobal;
        }
        return DefaultTemplates.forEvent(event);
    }

    @CheckForNull
    private static String firstNonEmpty(@CheckForNull String a, @CheckForNull String b) {
        if (a != null && !a.isEmpty()) {
            return a;
        }
        if (b != null && !b.isEmpty()) {
            return b;
        }
        return null;
    }
}
