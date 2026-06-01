package io.jenkins.plugins.slackbuildevents;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;

/**
 * {@code ${SLACK_BUILD_URL}} — the absolute build URL, always normalized to end with
 * a trailing {@code /} so templates can safely append {@code console} etc.
 *
 * <p>If the Jenkins root URL is not configured, {@code getAbsoluteUrl()} may throw;
 * the macro then falls back gracefully to an empty string.
 */
@Extension
public class SlackBuildUrlTokenMacro extends AbstractSlackRunMacro {

    public SlackBuildUrlTokenMacro() {
        super("SLACK_BUILD_URL");
    }

    @Override
    @NonNull
    @SuppressWarnings("deprecation") // getAbsoluteUrl: root-URL dependence is intentional and guarded
    protected String compute(@NonNull Run<?, ?> run, @NonNull TaskListener listener) {
        try {
            String url = run.getAbsoluteUrl();
            if (url.isEmpty()) {
                return "";
            }
            return url.endsWith("/") ? url : url + "/";
        } catch (IllegalStateException e) {
            // Jenkins root URL not configured.
            return "";
        } catch (RuntimeException e) {
            return "";
        }
    }
}
