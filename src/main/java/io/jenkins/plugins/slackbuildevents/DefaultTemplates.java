package io.jenkins.plugins.slackbuildevents;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Built-in, language-neutral default templates (token-macro syntax).
 *
 * <p>The plugin intentionally ships no organization-specific content. Localized or
 * deployment-specific wording (e.g. a Korean "배포" message) is injected through
 * global/rule template overrides in configuration, never here.
 */
final class DefaultTemplates {

    private DefaultTemplates() {}

    static final String START =
            "🔵 *Build Started*\n"
            + "*Job*: ${ENV,var=\"JOB_NAME\"}\n"
            + "*Build*: #${ENV,var=\"BUILD_NUMBER\"}\n"
            + "*Branch*: ${SLACK_GIT_BRANCH}\n"
            + "*Started by*: ${SLACK_DEPLOYER}\n"
            + "<${SLACK_BUILD_URL}console|Console>";

    static final String SUCCESS = completed("✅ *Build Succeeded*", "<${SLACK_BUILD_URL}|Build log>");
    static final String FAILURE = completed("❌ *Build Failed*", "<${SLACK_BUILD_URL}console|Console>");
    static final String UNSTABLE = completed("⚠️ *Build Unstable*", "<${SLACK_BUILD_URL}console|Console>");
    static final String ABORTED = completed("⏹️ *Build Aborted*", "<${SLACK_BUILD_URL}console|Console>");
    static final String NOT_BUILT = completed("⏭️ *Build Not Built*", "<${SLACK_BUILD_URL}console|Console>");

    /** Completion-event body: identical fields across results; only header + link differ. */
    private static String completed(String header, String link) {
        return header + "\n"
                + "*Job*: ${ENV,var=\"JOB_NAME\"}\n"
                + "*Build*: #${ENV,var=\"BUILD_NUMBER\"}\n"
                + "*Branch*: ${SLACK_GIT_BRANCH}\n"
                + "*Commit*: ${SLACK_GIT_COMMIT}\n"
                + "*By*: ${SLACK_DEPLOYER}\n"
                + "*Duration*: ${SLACK_DURATION}\n"
                + link;
    }

    @NonNull
    static String forEvent(@NonNull EventType event) {
        switch (event) {
            case START:
                return START;
            case SUCCESS:
                return SUCCESS;
            case FAILURE:
                return FAILURE;
            case UNSTABLE:
                return UNSTABLE;
            case ABORTED:
                return ABORTED;
            case NOT_BUILT:
                return NOT_BUILT;
            default:
                return "";
        }
    }
}
