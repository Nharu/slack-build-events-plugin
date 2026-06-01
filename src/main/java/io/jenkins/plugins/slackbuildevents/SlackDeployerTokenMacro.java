package io.jenkins.plugins.slackbuildevents;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Cause;
import hudson.model.Run;
import hudson.model.TaskListener;

/**
 * {@code ${SLACK_DEPLOYER}} — who triggered the build.
 *
 * <p>Fallback chain: triggering user id → upstream project → {@code "Jenkins"}.
 */
@Extension
public class SlackDeployerTokenMacro extends AbstractSlackRunMacro {

    public SlackDeployerTokenMacro() {
        super("SLACK_DEPLOYER");
    }

    @Override
    @NonNull
    protected String compute(@NonNull Run<?, ?> run, @NonNull TaskListener listener) {
        for (Cause cause : run.getCauses()) {
            if (cause instanceof Cause.UserIdCause) {
                String userId = ((Cause.UserIdCause) cause).getUserId();
                if (userId != null && !userId.isEmpty()) {
                    return userId;
                }
            }
        }
        for (Cause cause : run.getCauses()) {
            if (cause instanceof Cause.UpstreamCause) {
                String upstream = ((Cause.UpstreamCause) cause).getUpstreamProject();
                if (upstream != null && !upstream.isEmpty()) {
                    return upstream;
                }
            }
        }
        return "Jenkins";
    }
}
