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

    /**
     * Render-scoped hint carrying the start-time branch snapshot from the event thread to the async
     * renderer. Set at the top of {@code NotificationDispatcher.render()} and cleared in its
     * {@code finally}, so it never leaks across pooled dispatch workers or a retry hand-off.
     */
    private static final ThreadLocal<String> START_BRANCH_HINT = new ThreadLocal<>();

    /** Sets the render-scoped start-branch hint; a null/empty value clears it (treated as "none"). */
    static void setStartBranchHint(@CheckForNull String hint) {
        if (hint != null && !hint.isEmpty()) {
            START_BRANCH_HINT.set(hint);
        } else {
            START_BRANCH_HINT.remove();
        }
    }

    /** Clears the render-scoped start-branch hint. Must run in a {@code finally} on every render path. */
    static void clearStartBranchHint() {
        START_BRANCH_HINT.remove();
    }

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
        // Start-time snapshot (captured on the event thread) wins when present, so start
        // notifications resolve the intended branch instead of racing checkout to N/A. An
        // empty/absent hint falls through to the existing, unchanged fallback chain.
        String hint = START_BRANCH_HINT.get();
        if (hint != null && !hint.isEmpty()) {
            return hint;
        }
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

    /**
     * Normalizes a configured git branch spec (e.g. {@code &#42;/main}) to a plain branch
     * name, or {@code null} if the spec cannot be reduced to a single concrete branch.
     *
     * <p>Order is load-bearing: {@code ${...}} expansion and prefix stripping run
     * <em>before</em> wildcard rejection, so a real spec like {@code &#42;/main} survives
     * (strip {@code &#42;/} → {@code main}) instead of being killed by the leading {@code &#42;}.
     * All prefix strips are anchored to the start, so a mid-name {@code origin/} or
     * {@code refs/heads/} is preserved.
     */
    @CheckForNull
    static String normalizeSpec(@CheckForNull String rawSpec, @NonNull EnvVars env) {
        if (rawSpec == null || rawSpec.isBlank()) {
            return null;
        }
        // Expand ${PARAM}; a residual ${ means an unresolved reference → give up (best-effort).
        String expanded = env.expand(rawSpec);
        if (expanded == null || expanded.contains("${")) {
            return null;
        }
        String s = expanded.trim();
        if (s.isEmpty()) {
            return null;
        }
        s = stripOneRefPrefix(s);
        // Reject only AFTER stripping, so */main (now "main") is accepted rather than killed by '*'.
        if (s.isEmpty() || "HEAD".equals(s) || s.startsWith(":") || hasWildcardOrControl(s)) {
            return null;
        }
        return s;
    }

    /** Strips exactly one leading ref prefix (first match); prefix-anchored only. */
    private static String stripOneRefPrefix(String s) {
        if (s.startsWith("refs/heads/")) {
            return s.substring("refs/heads/".length());
        }
        if (s.startsWith("refs/tags/")) {
            return s.substring("refs/tags/".length());
        }
        if (s.startsWith("refs/remotes/")) {
            String rest = s.substring("refs/remotes/".length());
            int slash = rest.indexOf('/');
            return slash >= 0 ? rest.substring(slash + 1) : rest;
        }
        if (s.startsWith("*/")) {
            return s.substring(2);
        }
        if (s.startsWith("origin/")) {
            return s.substring("origin/".length());
        }
        return s;
    }

    private static boolean hasWildcardOrControl(String s) {
        for (int i = 0; i < s.length(); i++) {
            if ("*?[]\\".indexOf(s.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * The single configured branch of a {@link hudson.plugins.git.GitSCM}, normalized, or
     * {@code null} for a non-git SCM, a multi-spec SCM, or when the git plugin is absent.
     */
    @CheckForNull
    static String configuredBranch(@CheckForNull hudson.scm.SCM scm, @NonNull EnvVars env) {
        try {
            if (!(scm instanceof hudson.plugins.git.GitSCM)) {
                return null;
            }
            java.util.List<hudson.plugins.git.BranchSpec> branches =
                    ((hudson.plugins.git.GitSCM) scm).getBranches();
            if (branches == null || branches.size() != 1) {
                return null;
            }
            return normalizeSpec(branches.get(0).getName(), env);
        } catch (Throwable t) {
            // git plugin absent (NoClassDefFoundError) or internal shape changed; degrade gracefully.
            return null;
        }
    }

    /**
     * Build-parameter environment for {@code ${PARAM}} expansion inside a branch spec. Only
     * {@link hudson.model.ParametersAction} values are included — never the full build env — and
     * secret parameters are excluded entirely: {@code Secret.toString()} returns plaintext, so a
     * spec like {@code &#42;/${PW}} must never be able to leak a password parameter into a message.
     */
    @NonNull
    static EnvVars parameterEnv(@NonNull Run<?, ?> run) {
        EnvVars env = new EnvVars();
        try {
            hudson.model.ParametersAction action = run.getAction(hudson.model.ParametersAction.class);
            if (action != null) {
                for (hudson.model.ParameterValue pv : action.getParameters()) {
                    if (pv == null || pv instanceof hudson.model.PasswordParameterValue) {
                        continue;
                    }
                    Object value = pv.getValue();
                    if (value instanceof hudson.util.Secret) {
                        continue;
                    }
                    String name = pv.getName();
                    if (name != null && value != null) {
                        env.put(name, String.valueOf(value));
                    }
                }
            }
        } catch (Throwable t) {
            // best-effort: return whatever parameters were collected.
        }
        return env;
    }

    /**
     * Snapshot of the branch a build is about to build, computed on the event thread at start
     * (before {@code checkout scm}), or {@code null} when no start-time source exists. Preferred
     * source is env {@code BRANCH_NAME} (multibranch, race-free); otherwise the configured single
     * GitSCM spec — a from-SCM pipeline read reflectively, or a freestyle {@link hudson.model.AbstractProject}.
     * Every optional-dependency access is wrapped so a {@code NoClassDefFoundError} never breaks a build
     * (the caller {@code handle()} only catches {@code RuntimeException}).
     */
    @CheckForNull
    static String captureStartBranch(@NonNull Run<?, ?> run, @NonNull TaskListener listener) {
        try {
            String branchName = envVar(run, listener, "BRANCH_NAME");
            if (branchName != null && !branchName.isEmpty()) {
                return branchName;
            }
            hudson.scm.SCM scm = configuredScm(run);
            return configuredBranch(scm, parameterEnv(run));
        } catch (Throwable t) {
            // git/workflow optional deps absent, or any other failure → no start hint.
            return null;
        }
    }

    /** Configured SCM at start: a from-SCM workflow definition (reflection) or a freestyle project. */
    @CheckForNull
    private static hudson.scm.SCM configuredScm(Run<?, ?> run) {
        hudson.model.Job<?, ?> job = run.getParent();
        hudson.scm.SCM fromWorkflow = scmFromWorkflowDefinition(job);
        if (fromWorkflow != null) {
            return fromWorkflow;
        }
        if (job instanceof hudson.model.AbstractProject) {
            return ((hudson.model.AbstractProject<?, ?>) job).getScm();
        }
        return null;
    }

    /**
     * Reads the configured SCM off a {@code CpsScmFlowDefinition} via reflection, so neither
     * workflow-cps nor workflow-job needs to be a compile dependency. Gated by definition class
     * name so a non-from-SCM definition (e.g. an inline {@code CpsFlowDefinition}) is rejected.
     */
    @CheckForNull
    private static hudson.scm.SCM scmFromWorkflowDefinition(Object job) {
        Object definition;
        try {
            definition = job.getClass().getMethod("getDefinition").invoke(job);
        } catch (Throwable t) {
            return null;
        }
        if (definition == null
                || !"org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition"
                        .equals(definition.getClass().getName())) {
            return null;
        }
        return scmFromGetScm(definition);
    }

    /**
     * Reflectively reads {@code getScm()} off a workflow definition object. Package-private and
     * class-name-agnostic so a unit test can drive it with a stub whose {@code getScm()} throws
     * (e.g. {@link NoClassDefFoundError}) and confirm it degrades to {@code null} without rethrowing.
     */
    @CheckForNull
    static hudson.scm.SCM scmFromGetScm(@NonNull Object definition) {
        try {
            Object scm = definition.getClass().getMethod("getScm").invoke(definition);
            return (scm instanceof hudson.scm.SCM) ? (hudson.scm.SCM) scm : null;
        } catch (Throwable t) {
            return null;
        }
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
