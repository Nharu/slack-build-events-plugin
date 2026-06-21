package io.jenkins.plugins.slackbuildevents;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;

/**
 * {@code ${SLACK_GIT_COMMIT}} — short commit SHA, or empty when unknown.
 *
 * <p>On start events (before {@code checkout scm}) the commit is typically not yet
 * populated, so start messages omit it.
 */
@Extension
public class SlackGitCommitTokenMacro extends AbstractSlackRunMacro {

    public SlackGitCommitTokenMacro() {
        super("SLACK_GIT_COMMIT");
    }

    @Override
    @NonNull
    protected String compute(@NonNull Run<?, ?> run, @NonNull TaskListener listener) {
        // Commit values are env/SCM-derived; escape mrkdwn control chars at the emit point.
        return SlackText.escape(GitMacroSupport.commit(run, listener));
    }
}
