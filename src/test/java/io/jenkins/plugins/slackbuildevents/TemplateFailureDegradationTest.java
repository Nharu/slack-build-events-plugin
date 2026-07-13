package io.jenkins.plugins.slackbuildevents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.util.List;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.tokenmacro.DataBoundTokenMacro;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;

/**
 * End-to-end reproduction that the two-pass render contract produces the intended body down each
 * branch, through the live async dispatch path (the runtime confirmation of the bytecode-verified
 * token-macro semantics). Covers:
 *
 * <ul>
 *   <li>DEGRADED (i): an unregistered token survives as {@code ${TOKEN}} while normal macros still
 *       render — the fix for the #14 all-or-nothing raw send.
 *   <li>DEGRADED (ii): a registered macro's exception becomes the {@code [Error replacing 'NAME' - msg]}
 *       literal.
 *   <li>DEGRADED injection defense: a hostile exception message cannot inject mrkdwn link markup.
 *   <li>FALLBACK: a non-macro throwable yields the marked message — never the raw template.
 * </ul>
 */
public class TemplateFailureDegradationTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private TestWebhookSender sender;

    @Before
    public void setUp() throws Exception {
        sender = new TestWebhookSender();
        SlackTestHelpers.installSeams(sender);
        SlackTestHelpers.addWebhookCredential("wh", "http://example.test/hook");
        SlackTestHelpers.config().setDefaultWebhookCredentialId("wh");
    }

    @Test
    public void unknownTokenDegradesButNormalMacrosStillRender() throws Exception {
        NotificationRule rule = SlackTestHelpers.rule("myjob", List.of("success"));
        rule.setSuccessTemplate("job=${ENV,var=\"JOB_NAME\"} bad=${DEFINITELY_UNKNOWN}");
        SlackTestHelpers.config().setRules(List.of(rule));

        j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
        SlackTestHelpers.awaitDispatch();

        String text = text(sender.bodies.get(0));
        // Only the unknown token stays literal; the normal macro renders (was all-or-nothing raw before).
        assertThat(text, containsString("${DEFINITELY_UNKNOWN}"));
        assertThat(text, containsString("job=myjob"));
    }

    @Test
    public void registeredMacroExceptionBecomesErrorLiteral() throws Exception {
        NotificationRule rule = SlackTestHelpers.rule("myjob", List.of("success"));
        rule.setSuccessTemplate("job=${ENV,var=\"JOB_NAME\"} x=${SLACK_TEST_MACRO_ERR}");
        SlackTestHelpers.config().setRules(List.of(rule));

        j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
        SlackTestHelpers.awaitDispatch();

        String text = text(sender.bodies.get(0));
        assertThat(text, containsString("[Error replacing 'SLACK_TEST_MACRO_ERR' -"));
        assertThat(text, containsString("job=myjob"));
    }

    @Test
    public void hostileMacroExceptionMessageCannotInjectLinkMarkup() throws Exception {
        NotificationRule rule = SlackTestHelpers.rule("myjob", List.of("success"));
        rule.setSuccessTemplate("x=${SLACK_TEST_HOSTILE}");
        SlackTestHelpers.config().setRules(List.of(rule));

        j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
        SlackTestHelpers.awaitDispatch();

        String text = text(sender.bodies.get(0));
        // The <url|label> markup carried in the exception message is neutralized by the whole-output escape.
        assertThat(text, containsString("&lt;https://evil|click&gt;"));
        assertThat(text, not(containsString("<https")));
    }

    @Test
    public void nonMacroThrowableProducesMarkedFallbackNeverRawTemplate() throws Exception {
        String template = "boom=${SLACK_TEST_RUNTIME}";
        NotificationRule rule = SlackTestHelpers.rule("myjob", List.of("success"));
        rule.setSuccessTemplate(template);
        SlackTestHelpers.config().setRules(List.of(rule));

        j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
        SlackTestHelpers.awaitDispatch();

        String text = text(sender.bodies.get(0));
        assertThat(text, containsString("⚠️ *Slack notification template failed to render*"));
        assertThat(text, containsString("*Job*: myjob"));
        // The raw template (and any unexpanded token) is never sent.
        assertThat(text, not(containsString("${")));
        assertThat(text, not(containsString(template)));
    }

    private static String text(String body) {
        return JSONObject.fromObject(body).getJSONArray("attachments").getJSONObject(0).getString("text");
    }

    /** Registered macro whose evaluate throws {@link MacroEvaluationException} → DEGRADED error literal. */
    @TestExtension
    public static class MacroErrorMacro extends DataBoundTokenMacro {
        @Override
        public boolean acceptsMacroName(String name) {
            return "SLACK_TEST_MACRO_ERR".equals(name);
        }

        @Override
        public String evaluate(AbstractBuild<?, ?> context, TaskListener listener, String macroName)
                throws MacroEvaluationException {
            throw new MacroEvaluationException("macro boom");
        }

        @Override
        public String evaluate(Run<?, ?> run, FilePath workspace, TaskListener listener, String macroName)
                throws MacroEvaluationException {
            throw new MacroEvaluationException("macro boom");
        }
    }

    /** Registered macro whose exception message carries link markup → tests DEGRADED injection defense. */
    @TestExtension
    public static class HostileMacroErrorMacro extends DataBoundTokenMacro {
        @Override
        public boolean acceptsMacroName(String name) {
            return "SLACK_TEST_HOSTILE".equals(name);
        }

        @Override
        public String evaluate(AbstractBuild<?, ?> context, TaskListener listener, String macroName)
                throws MacroEvaluationException {
            throw new MacroEvaluationException("<https://evil|click>");
        }

        @Override
        public String evaluate(Run<?, ?> run, FilePath workspace, TaskListener listener, String macroName)
                throws MacroEvaluationException {
            throw new MacroEvaluationException("<https://evil|click>");
        }
    }

    /** Registered macro that throws a plain {@link RuntimeException} → both passes throw → FALLBACK. */
    @TestExtension
    public static class RuntimeFailingMacro extends DataBoundTokenMacro {
        @Override
        public boolean acceptsMacroName(String name) {
            return "SLACK_TEST_RUNTIME".equals(name);
        }

        @Override
        public String evaluate(AbstractBuild<?, ?> context, TaskListener listener, String macroName) {
            throw new RuntimeException("runtime boom");
        }

        @Override
        public String evaluate(Run<?, ?> run, FilePath workspace, TaskListener listener, String macroName) {
            throw new RuntimeException("runtime boom");
        }
    }
}
