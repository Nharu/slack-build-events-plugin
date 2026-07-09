package io.jenkins.plugins.slackbuildevents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Run;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.TaskListener;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.sf.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/** (f) template/macro expansion parity, (i) macro-injection defense + JSON escaping. */
public class TemplateExpansionTest {

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
    public void expandsStandardAndCustomMacros() throws Exception {
        NotificationRule rule = SlackTestHelpers.rule("myjob", List.of("success"));
        rule.setSuccessTemplate("job=${ENV,var=\"JOB_NAME\"} by=${SLACK_DEPLOYER} url=${SLACK_BUILD_URL}");
        SlackTestHelpers.config().setRules(List.of(rule));

        j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
        SlackTestHelpers.awaitDispatch();

        String text = text(sender.bodies.get(0));
        assertThat(text, containsString("job=myjob"));
        assertThat(text, containsString("by=Jenkins"));
        assertThat(text, containsString("url=http"));
        assertThat(text, containsString("/job/myjob/1/"));
    }

    @Test
    public void userEnvIsNotReExpandedAndIsJsonEscaped() throws Exception {
        NotificationRule rule = SlackTestHelpers.rule("myjob", List.of("success"));
        rule.setSuccessTemplate("branch=${SLACK_GIT_BRANCH}");
        SlackTestHelpers.config().setRules(List.of(rule));

        FreeStyleProject p = j.createFreeStyleProject("myjob");
        // User-controlled value containing both a macro token and a double quote.
        String injected = "x-${SLACK_DEPLOYER}-\"q";
        p.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("GIT_BRANCH", "def")));
        j.assertBuildStatusSuccess(
                p.scheduleBuild2(0, new ParametersAction(new StringParameterValue("GIT_BRANCH", injected))));
        SlackTestHelpers.awaitDispatch();

        // Body must be valid JSON (no breakage from the quote).
        String body = sender.bodies.get(0);
        String text = text(body);
        // The inner ${SLACK_DEPLOYER} token must survive verbatim (single-pass, no re-expansion).
        assertThat(text, containsString("x-${SLACK_DEPLOYER}-"));
        assertThat(text, containsString("\"q"));
    }

    @Test
    public void gitBranchMacroEscapesLinkInjection() throws Exception {
        NotificationRule rule = SlackTestHelpers.rule("myjob", List.of("success"));
        rule.setSuccessTemplate("branch=${SLACK_GIT_BRANCH}");
        SlackTestHelpers.config().setRules(List.of(rule));

        FreeStyleProject p = j.createFreeStyleProject("myjob");
        // Hostile branch name carrying <url|label> link markup (env GIT_BRANCH = primary vector).
        String hostileBranch = "origin/<https://evil|click>";
        p.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("GIT_BRANCH", "def")));
        j.assertBuildStatusSuccess(
                p.scheduleBuild2(0, new ParametersAction(new StringParameterValue("GIT_BRANCH", hostileBranch))));
        SlackTestHelpers.awaitDispatch();

        String text = text(sender.bodies.get(0));
        // Control chars escaped → the injected link markup is neutralized (no raw '<https').
        assertThat(text, containsString("branch=origin/&lt;https://evil|click&gt;"));
        assertThat(text, not(containsString("<https")));
    }

    @Test
    public void buildUrlLinkMarkupSurvivesWhileBranchIsEscaped() throws Exception {
        NotificationRule rule = SlackTestHelpers.rule("myjob", List.of("success"));
        // Admin-authored <${SLACK_BUILD_URL}|Console> link markup must survive (the URL macro is
        // excluded from escaping), while the SCM-derived branch value is escaped.
        rule.setSuccessTemplate("<${SLACK_BUILD_URL}|Console> branch=${SLACK_GIT_BRANCH}");
        SlackTestHelpers.config().setRules(List.of(rule));

        FreeStyleProject p = j.createFreeStyleProject("myjob");
        p.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("GIT_BRANCH", "def")));
        j.assertBuildStatusSuccess(
                p.scheduleBuild2(0, new ParametersAction(new StringParameterValue("GIT_BRANCH", "x<y>"))));
        SlackTestHelpers.awaitDispatch();

        String text = text(sender.bodies.get(0));
        // Link markup intact: literal <…|Console> around the (unescaped) build URL.
        assertThat(text, containsString("<http"));
        assertThat(text, containsString("|Console>"));
        // Branch value escaped.
        assertThat(text, containsString("branch=x&lt;y&gt;"));
    }

    @Test
    public void durationMacroExpandsLocaleNeutrallyEvenUnderGermanDefault() throws Exception {
        // Build first with no rule installed, so the build's own completion dispatches nothing.
        // That lets us inject a large recorded duration and fire the notification deterministically,
        // instead of relying on the real (sub-second) elapsed time — which would never surface the
        // localized "Stunden"/"Minuten" words and so make the regression assertion vacuous.
        FreeStyleBuild build = j.buildAndAssertSuccess(j.createFreeStyleProject("dur-e2e"));
        setLongField(build, "duration", 7_503_000L); // 2h 5m 3s

        NotificationRule rule = SlackTestHelpers.rule("dur-e2e", List.of("success"));
        rule.setSuccessTemplate("dur=${SLACK_DURATION}");
        SlackTestHelpers.config().setRules(List.of(rule));

        Locale saved = Locale.getDefault();
        try {
            // The dispatch thread renders under whatever the JVM default is; a German default is what
            // makes the core formatter emit "2 Stunden 5 Minuten". The language-neutral formatter must
            // ignore the locale and render "2h 5m" regardless.
            Locale.setDefault(Locale.GERMANY);
            new SlackNotifyRunListener().onCompleted(build, TaskListener.NULL);
            SlackTestHelpers.awaitDispatch();
        } finally {
            Locale.setDefault(saved);
        }

        String text = text(sender.bodies.get(0));
        assertThat(text, containsString("dur=2h 5m"));
        assertThat(text, not(containsString("Stunden")));
        assertThat(text, not(containsString("Minuten")));
        assertTrue(
                "duration should render as language-neutral symbols, was: " + text,
                Pattern.compile("dur=\\d+(ms|s|m|h|d)( \\d+(ms|s|m|h|d))?").matcher(text).find());
    }

    private static void setLongField(Run<?, ?> build, String name, long value) throws Exception {
        Field field = Run.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(build, value);
    }

    private static String text(String body) {
        JSONObject json = JSONObject.fromObject(body);
        return json.getJSONArray("attachments").getJSONObject(0).getString("text");
    }
}
