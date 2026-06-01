package io.jenkins.plugins.slackbuildevents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;

import hudson.model.FreeStyleProject;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import java.util.List;
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

    private static String text(String body) {
        JSONObject json = JSONObject.fromObject(body);
        return json.getJSONArray("attachments").getJSONObject(0).getString("text");
    }
}
