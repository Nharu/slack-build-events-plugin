package io.jenkins.plugins.slackbuildevents;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;

/**
 * {@code ${SLACK_DURATION}} — the build's elapsed time, locale-safe.
 *
 * <p>Uses the recorded duration when available; otherwise computes it live from the
 * start time (the duration field is not yet set when a completion listener fires).
 */
@Extension
public class SlackDurationTokenMacro extends AbstractSlackRunMacro {

    public SlackDurationTokenMacro() {
        super("SLACK_DURATION");
    }

    @Override
    @NonNull
    protected String compute(@NonNull Run<?, ?> run, @NonNull TaskListener listener) {
        long duration = run.getDuration();
        if (duration <= 0L) {
            duration = System.currentTimeMillis() - run.getStartTimeInMillis();
        }
        if (duration < 0L) {
            duration = 0L;
        }
        return DurationFormat.format(duration);
    }
}
