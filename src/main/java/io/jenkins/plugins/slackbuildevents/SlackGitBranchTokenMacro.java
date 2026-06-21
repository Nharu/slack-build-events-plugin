package io.jenkins.plugins.slackbuildevents;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;

/**
 * {@code ${SLACK_GIT_BRANCH}} — branch name, or {@code "N/A"} when unknown.
 */
@Extension
public class SlackGitBranchTokenMacro extends AbstractSlackRunMacro {

    public SlackGitBranchTokenMacro() {
        super("SLACK_GIT_BRANCH");
    }

    @Override
    @NonNull
    protected String compute(@NonNull Run<?, ?> run, @NonNull TaskListener listener) {
        // Branch names are attacker-influenceable; escape mrkdwn control chars at the emit point.
        return SlackText.escape(GitMacroSupport.branch(run, listener));
    }
}
