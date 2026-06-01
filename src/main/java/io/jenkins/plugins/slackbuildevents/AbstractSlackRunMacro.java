package io.jenkins.plugins.slackbuildevents;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import org.jenkinsci.plugins.tokenmacro.DataBoundTokenMacro;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;

/**
 * Base for the plugin's {@code ${SLACK_*}} token macros.
 *
 * <p>Wires both the legacy {@link AbstractBuild} evaluation and the
 * {@link Run}-based (pipeline-friendly) evaluation to a single {@link #compute}
 * method. All five concrete macros read only Run-attached, read-only data, so they
 * need no workspace and work on ephemeral/pod agents.
 */
abstract class AbstractSlackRunMacro extends DataBoundTokenMacro {

    private final String macroName;

    protected AbstractSlackRunMacro(String macroName) {
        this.macroName = macroName;
    }

    @Override
    public boolean acceptsMacroName(String name) {
        return macroName.equals(name);
    }

    @Override
    public String evaluate(AbstractBuild<?, ?> context, TaskListener listener, String name)
            throws MacroEvaluationException, IOException, InterruptedException {
        return compute(context, listener);
    }

    @Override
    public String evaluate(Run<?, ?> run, FilePath workspace, TaskListener listener, String name)
            throws MacroEvaluationException, IOException, InterruptedException {
        return compute(run, listener);
    }

    /** Computes the macro value; implementations must not throw (return a fallback instead). */
    @NonNull
    protected abstract String compute(@NonNull Run<?, ?> run, @NonNull TaskListener listener);
}
