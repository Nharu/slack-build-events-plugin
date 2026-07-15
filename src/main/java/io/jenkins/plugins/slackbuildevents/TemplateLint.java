package io.jenkins.plugins.slackbuildevents;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Util;
import hudson.util.FormValidation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;

/**
 * Save-time lint for admin-authored notification templates. Non-blocking guidance only
 * (never an error): it nudges admins away from raw, unescaped value references and warns
 * about references that no longer expand.
 *
 * <p>Two independent concerns:
 * <ul>
 *   <li><b>Unescaped {@code ${ENV,var="…"}} references</b> — the {@code ENV} macro is a
 *       parser-stage macro that survives the render path, and its value is inserted without
 *       Slack mrkdwn escaping (unlike the plugin's {@code ${SLACK_*}} macros). A crafted
 *       SCM/PR-derived value can inject {@code <url|label>} link markup, so these are flagged
 *       and, where a safe escaped macro exists, the admin is pointed to it.</li>
 *   <li><b>Plain {@code ${VAR}} / {@code $VAR} references</b> — these are not recognized
 *       macros, so the render path stops expanding them and the whole message falls back to
 *       raw text. A portability note (not a security warning) points that out.</li>
 * </ul>
 *
 * <p>The set of "recognized" names is the plugin's own {@code ${SLACK_*}} macros plus every
 * name any registered {@link TokenMacro} advertises via {@code getAcceptedMacroNames()}, so a
 * reference that a macro owns (and therefore still expands) is never flagged.
 */
final class TemplateLint {

    private TemplateLint() {}

    /**
     * The plugin's own {@code ${SLACK_*}} macros. {@link AbstractSlackRunMacro} accepts these
     * via {@code acceptsMacroName} but does not advertise them through
     * {@code getAcceptedMacroNames()}, so they are listed explicitly here.
     */
    private static final Set<String> SLACK_MACROS = Set.of(
            "SLACK_DEPLOYER", "SLACK_BUILD_URL", "SLACK_DURATION", "SLACK_GIT_BRANCH", "SLACK_GIT_COMMIT");

    /**
     * Infrastructure env vars treated as not attacker-influenced, so an {@code ${ENV,var="…"}}
     * reference to one of them is not flagged. Deliberately excludes user-settable display values such
     * as {@code BUILD_DISPLAY_NAME}. Residual: {@code JOB_NAME} / {@code JOB_BASE_NAME} /
     * {@code BUILD_TAG} can carry PR-influenced text in multibranch jobs, but are kept here — the
     * plugin's own built-in default templates reference {@code ${ENV,var="JOB_NAME"}}, so exempting it
     * avoids warning on those defaults; a broader multibranch audit of this allowlist is tracked
     * separately.
     */
    private static final Set<String> SAFE_ENV = Set.of(
            "JOB_NAME", "JOB_BASE_NAME", "BUILD_NUMBER", "BUILD_ID", "BUILD_TAG",
            "JENKINS_URL", "BUILD_URL", "JOB_URL", "NODE_NAME", "EXECUTOR_NUMBER", "WORKSPACE");

    /** SCM/PR/commit env vars that have a safe, escaped {@code ${SLACK_*}} replacement. */
    private static final Map<String, String> DENYLIST_WITH_EQUIVALENT = Map.of(
            "GIT_BRANCH", "SLACK_GIT_BRANCH",
            "GIT_LOCAL_BRANCH", "SLACK_GIT_BRANCH",
            "GIT_COMMIT", "SLACK_GIT_COMMIT");

    /** SCM/PR/commit env vars that are attacker-influenced but have no {@code ${SLACK_*}} equivalent. */
    private static final Set<String> DENYLIST_NO_EQUIVALENT = Set.of(
            "GIT_URL", "GIT_AUTHOR_NAME", "GIT_AUTHOR_EMAIL", "GIT_COMMITTER_NAME", "GIT_COMMITTER_EMAIL",
            "GIT_PREVIOUS_COMMIT", "GIT_PREVIOUS_SUCCESSFUL_COMMIT",
            "BRANCH_NAME", "CHANGE_ID", "CHANGE_URL", "CHANGE_TITLE", "CHANGE_AUTHOR",
            "CHANGE_AUTHOR_DISPLAY_NAME", "CHANGE_AUTHOR_EMAIL", "CHANGE_BRANCH", "CHANGE_FORK", "CHANGE_TARGET",
            "ghprbPullTitle", "ghprbPullLongDescription", "ghprbSourceBranch", "ghprbPullDescription",
            "ghprbActualCommitAuthor", "ghprbActualCommitAuthorEmail", "ghprbPullAuthorLogin");

    /** A {@code ${…}} whose whole content (before the closing brace) is a plain identifier — no macro args. */
    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z0-9_.]+");

    /** Case-sensitive {@code ENV} macro invocation: content begins with {@code ENV} then a comma. */
    private static final Pattern ENV_START = Pattern.compile("^ *ENV *,.*", Pattern.DOTALL);

    /** The double-quoted {@code var="NAME"} argument of an {@code ENV} macro (single quotes do not expand). */
    private static final Pattern VAR_ARG = Pattern.compile("var *= *\"([^\"]*)\"");

    /**
     * Lints one template field's value and returns non-blocking guidance (never an error).
     * Shared by every template-field {@code doCheck} on both descriptors.
     */
    @NonNull
    static FormValidation check(String value) {
        String template = Util.fixEmpty(value);
        if (template == null) {
            return FormValidation.ok();
        }
        Scan scan = scan(template);
        Set<String> known = knownMacroNames();

        List<String> withEquivalent = new ArrayList<>();
        Set<String> noEquivalent = new TreeSet<>();
        Set<String> otherEnv = new TreeSet<>();
        for (String content : scan.delimitedContents) {
            String var = envFormVar(content);
            if (var == null || SAFE_ENV.contains(var)) {
                continue;
            }
            String equivalent = DENYLIST_WITH_EQUIVALENT.get(var);
            if (equivalent != null) {
                withEquivalent.add(var + " → ${" + equivalent + "}");
            } else if (DENYLIST_NO_EQUIVALENT.contains(var)) {
                noEquivalent.add(var);
            } else {
                otherEnv.add(var);
            }
        }
        Set<String> rawFallback = rawFallbackNames(scan, known);

        List<FormValidation> parts = new ArrayList<>();
        if (!withEquivalent.isEmpty()) {
            parts.add(FormValidation.warning(
                    "These ${ENV,var=\"…\"} references insert SCM/PR-derived values without Slack escaping, "
                            + "so a crafted value (e.g. a branch name) can inject <url|label> link markup. "
                            + "Use the escaped macro instead: " + String.join(", ", withEquivalent) + "."));
        }
        if (!noEquivalent.isEmpty()) {
            parts.add(FormValidation.warning(
                    "These ${ENV,var=\"…\"} references insert SCM/PR/commit-derived values without Slack "
                            + "escaping and have no built-in escaped equivalent; make sure the source is trusted: "
                            + String.join(", ", noEquivalent) + "."));
        }
        if (!otherEnv.isEmpty()) {
            parts.add(FormValidation.warning(
                    "These ${ENV,var=\"…\"} values are inserted without Slack escaping. If any can be "
                            + "influenced by an untrusted source (e.g. a build parameter), it can inject link "
                            + "markup: " + String.join(", ", otherEnv) + "."));
        }
        if (!rawFallback.isEmpty()) {
            parts.add(FormValidation.ok(
                    "These plain ${…}/$… references are not recognized macros and will stop expanding — "
                            + "the whole message then falls back to its raw text. Use a ${SLACK_*} macro or "
                            + "${ENV,var=\"…\"} instead: " + String.join(", ", rawFallback) + "."));
        }

        if (parts.isEmpty()) {
            return FormValidation.ok();
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return FormValidation.aggregate(parts);
    }

    /**
     * Plain {@code ${VAR}} / {@code $VAR} references in a template whose name no registered macro
     * owns — i.e. the references that will stop expanding (and force a whole-message raw fallback)
     * once the render path no longer runs the plain-env substitution pass. Used by the one-time
     * migration sweep. {@code $$}-escaped forms and numeric {@code $5} forms are not references and
     * are excluded.
     */
    @NonNull
    static Set<String> rawFallbackNames(String template) {
        return rawFallbackNames(template, knownMacroNames());
    }

    /**
     * Same as {@link #rawFallbackNames(String)} but reuses a caller-computed known-macro-name set, so a
     * sweep over many templates computes it once instead of once per template.
     */
    @NonNull
    static Set<String> rawFallbackNames(String template, Set<String> known) {
        String t = Util.fixEmpty(template);
        if (t == null) {
            return Collections.emptySet();
        }
        return rawFallbackNames(scan(t), known);
    }

    private static Set<String> rawFallbackNames(Scan scan, Set<String> known) {
        Set<String> out = new TreeSet<>();
        for (String content : scan.delimitedContents) {
            if (PLAIN_NAME.matcher(content).matches() && !known.contains(content)) {
                out.add(content);
            }
        }
        for (String name : scan.nonDelimitedNames) {
            if (!known.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    private static String envFormVar(String content) {
        if (!ENV_START.matcher(content).matches()) {
            return null;
        }
        Matcher m = VAR_ARG.matcher(content);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Every macro name recognized on this controller: the plugin's own {@code ${SLACK_*}} set plus
     * whatever each registered {@link TokenMacro} advertises. Degrades to the hardcoded set alone if
     * the extension list is unavailable (e.g. a pure unit context). A single misbehaving macro is
     * skipped rather than allowed to break the lint.
     */
    static Set<String> knownMacroNames() {
        Set<String> names = new HashSet<>(SLACK_MACROS);
        try {
            for (TokenMacro macro : TokenMacro.all()) {
                try {
                    List<String> accepted = macro.getAcceptedMacroNames();
                    if (accepted != null) {
                        names.addAll(accepted);
                    }
                } catch (Throwable t) {
                    // one macro misbehaving must not break the lint.
                }
            }
        } catch (Throwable t) {
            // token-macro extension list unavailable; fall back to the hardcoded set.
        }
        return names;
    }

    /**
     * Splits a template into its {@code ${…}} delimited-token contents and its {@code $NAME}
     * non-delimited names, honoring {@code $$} as a literal-dollar escape (so an escaped
     * {@code $${…}} contributes nothing). Mirrors the token-macro parser's dollar handling closely
     * enough for lint purposes; a stray {@code }} inside a quoted macro argument is a best-effort
     * edge that is not handled.
     */
    private static Scan scan(String s) {
        Scan out = new Scan();
        int i = 0;
        int n = s.length();
        while (i < n) {
            if (s.charAt(i) != '$') {
                i++;
                continue;
            }
            if (i + 1 >= n) {
                break;
            }
            char next = s.charAt(i + 1);
            if (next == '$') {
                i += 2; // $$ → literal '$', not a reference.
                continue;
            }
            if (next == '{') {
                int close = s.indexOf('}', i + 2);
                if (close < 0) {
                    i += 2; // unterminated ${ … → not a token.
                    continue;
                }
                out.delimitedContents.add(s.substring(i + 2, close));
                i = close + 1;
                continue;
            }
            if (isNameStart(next)) {
                int j = i + 1;
                while (j < n && isNamePart(s.charAt(j))) {
                    j++;
                }
                out.nonDelimitedNames.add(s.substring(i + 1, j));
                i = j;
                continue;
            }
            i++; // $5, "$ ", etc. → literal.
        }
        return out;
    }

    private static boolean isNameStart(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
    }

    private static boolean isNamePart(char c) {
        return isNameStart(c) || (c >= '0' && c <= '9');
    }

    private static final class Scan {
        private final List<String> delimitedContents = new ArrayList<>();
        private final List<String> nonDelimitedNames = new ArrayList<>();
    }
}
