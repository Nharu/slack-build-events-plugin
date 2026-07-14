package io.jenkins.plugins.slackbuildevents;

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One-time, load-time migration notice.
 *
 * <p>The render path expands templates with token-macro's {@code expand()} (registered macros only),
 * not {@code expandAll()} (which also ran a leading plain-env substitution pass). A consequence is
 * that an already-saved custom template referencing a plain {@code ${VAR}} / {@code $VAR} whose name
 * is not a recognized macro no longer expands — the whole message is sent as raw text.
 *
 * <p>That regression is invisible to the save-time field lint (it only fires while an admin edits the
 * form), so this sweep runs once at startup, after configuration is loaded, and logs a single WARNING
 * naming the affected references. It is a migration aid, not a security control.
 */
public final class TemplateMigrationSweep {

    private static final Logger LOGGER = Logger.getLogger(TemplateMigrationSweep.class.getName());

    private TemplateMigrationSweep() {}

    @Initializer(after = InitMilestone.SYSTEM_CONFIG_ADAPTED)
    public static void warnOnRawFallbackTemplates() {
        try {
            SlackNotifierGlobalConfig config = SlackNotifierGlobalConfig.get();
            if (config == null) {
                return;
            }
            Set<String> names = new LinkedHashSet<>();
            for (EventType event : EventType.values()) {
                names.addAll(TemplateLint.rawFallbackNames(config.defaultTemplateFor(event)));
            }
            for (NotificationRule rule : config.getRules()) {
                for (EventType event : EventType.values()) {
                    names.addAll(TemplateLint.rawFallbackNames(rule.templateFor(event)));
                }
            }
            if (!names.isEmpty()) {
                // Plain string (no log parameters) so the literal ${...}/{…} in the message is not
                // interpreted as a java.util.logging MessageFormat pattern.
                LOGGER.warning(
                        "Slack Templated Notifier: these plain ${VAR}/$VAR references in your configured "
                                + "templates are not recognized macros and will no longer expand — the affected "
                                + "messages are now sent as raw, unexpanded text. Replace each with a ${SLACK_*} "
                                + "macro or ${ENV,var=\"...\"}: " + String.join(", ", names));
            }
        } catch (Throwable t) {
            // The migration notice is best-effort; never let it interfere with startup.
            LOGGER.log(Level.FINE, "Slack template migration sweep skipped", t);
        }
    }
}
