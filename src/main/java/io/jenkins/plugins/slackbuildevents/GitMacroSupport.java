package io.jenkins.plugins.slackbuildevents;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import java.lang.reflect.Method;

/**
 * Shared resolution for the git commit/branch macros.
 *
 * <p>All sources are Run-attached, so they work even when the workspace is gone
 * (e.g. pod agents at completion time). The git plugin and scm-api are optional
 * dependencies, so every access to their classes is guarded against
 * {@link NoClassDefFoundError} to degrade gracefully on controllers without them.
 */
final class GitMacroSupport {

    private GitMacroSupport() {}

    /** Short commit SHA: env {@code GIT_COMMIT} (7 chars) → git BuildData → SCM revision → "". */
    @NonNull
    static String commit(@NonNull Run<?, ?> run, @NonNull TaskListener listener) {
        String env = envVar(run, listener, "GIT_COMMIT");
        if (env != null && !env.isEmpty()) {
            return shorten(env);
        }
        String fromBuildData = commitFromBuildData(run);
        if (fromBuildData != null && !fromBuildData.isEmpty()) {
            return shorten(fromBuildData);
        }
        String fromScm = commitFromScmRevision(run);
        if (fromScm != null && !fromScm.isEmpty()) {
            return shorten(fromScm);
        }
        return "";
    }

    /** Branch name: env {@code GIT_BRANCH} → git BuildData → SCM head → "N/A". */
    @NonNull
    static String branch(@NonNull Run<?, ?> run, @NonNull TaskListener listener) {
        String env = envVar(run, listener, "GIT_BRANCH");
        if (env != null && !env.isEmpty()) {
            return env;
        }
        String fromBuildData = branchFromBuildData(run);
        if (fromBuildData != null && !fromBuildData.isEmpty()) {
            return fromBuildData;
        }
        String fromScm = branchFromScmRevision(run);
        if (fromScm != null && !fromScm.isEmpty()) {
            return fromScm;
        }
        return "N/A";
    }

    private static String shorten(String sha) {
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }

    @CheckForNull
    private static String envVar(Run<?, ?> run, TaskListener listener, String name) {
        try {
            EnvVars env = run.getEnvironment(listener);
            return env.get(name);
        } catch (IOException | InterruptedException | RuntimeException e) {
            return null;
        }
    }

    @CheckForNull
    private static String commitFromBuildData(Run<?, ?> run) {
        try {
            hudson.plugins.git.util.BuildData data = run.getAction(hudson.plugins.git.util.BuildData.class);
            if (data != null) {
                hudson.plugins.git.Revision rev = data.getLastBuiltRevision();
                if (rev != null && rev.getSha1String() != null) {
                    return rev.getSha1String();
                }
            }
        } catch (Throwable t) {
            // git plugin absent or internal shape changed; fall through.
        }
        return null;
    }

    @CheckForNull
    private static String branchFromBuildData(Run<?, ?> run) {
        try {
            hudson.plugins.git.util.BuildData data = run.getAction(hudson.plugins.git.util.BuildData.class);
            if (data != null) {
                hudson.plugins.git.Revision rev = data.getLastBuiltRevision();
                if (rev != null) {
                    for (hudson.plugins.git.Branch b : rev.getBranches()) {
                        if (b.getName() != null && !b.getName().isEmpty()) {
                            return b.getName();
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // git plugin absent; fall through.
        }
        return null;
    }

    @CheckForNull
    private static String commitFromScmRevision(Run<?, ?> run) {
        try {
            jenkins.scm.api.SCMRevisionAction action = run.getAction(jenkins.scm.api.SCMRevisionAction.class);
            if (action != null) {
                jenkins.scm.api.SCMRevision rev = action.getRevision();
                // git's SCMRevisionImpl exposes getHash(); read reflectively to avoid a hard type ref.
                try {
                    Method getHash = rev.getClass().getMethod("getHash");
                    Object hash = getHash.invoke(rev);
                    if (hash != null) {
                        return hash.toString();
                    }
                } catch (ReflectiveOperationException ignored) {
                    // not a git revision; no commit hash available.
                }
            }
        } catch (Throwable t) {
            // scm-api absent; fall through.
        }
        return null;
    }

    @CheckForNull
    private static String branchFromScmRevision(Run<?, ?> run) {
        try {
            jenkins.scm.api.SCMRevisionAction action = run.getAction(jenkins.scm.api.SCMRevisionAction.class);
            if (action != null) {
                return action.getRevision().getHead().getName();
            }
        } catch (Throwable t) {
            // scm-api absent (NoClassDefFoundError) or revision unavailable; degrade gracefully.
        }
        return null;
    }
}
