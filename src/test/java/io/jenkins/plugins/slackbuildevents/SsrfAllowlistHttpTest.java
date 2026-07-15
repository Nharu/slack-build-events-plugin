package io.jenkins.plugins.slackbuildevents;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertTrue;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;

/**
 * Send-time SSRF enforcement over real HTTP: a host outside the allowlist (or a plain-http URL
 * under https-only) is never POSTed, and the block WARNING exposes only scheme+host, not the
 * secret webhook path/token.
 *
 * <p>The positive AND-combo (host in allowlist AND https-only satisfied together → allowed) is
 * covered at the unit level by {@link WebhookUrlPolicyTest}, so it is not re-exercised end-to-end here.
 */
public class SsrfAllowlistHttpTest {

    /** A webhook whose path carries a secret token — the block log must never echo it. */
    private static final String SECRET_PATH = "/services/T000/B000/SUPERSECRETTOKEN";

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Rule
    public WireMockRule wireMock = new WireMockRule(options().dynamicPort());

    @Rule
    public LoggerRule logging = new LoggerRule();

    @Before
    public void setUp() throws Exception {
        SlackTestHelpers.installSeams(new WebhookSender());
        SlackTestHelpers.addWebhookCredential("wh", wireMock.baseUrl() + SECRET_PATH);
        SlackTestHelpers.config().setDefaultWebhookCredentialId("wh");
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("myjob", List.of("start"))));
        logging.record(NotificationDispatcher.class, Level.WARNING).capture(20);
        stubFor(post(anyUrl()).willReturn(aResponse().withStatus(200)));
    }

    @Test
    public void hostOutsideAllowlistIsNotSent() throws Exception {
        SlackTestHelpers.config().setWebhookHostAllowlist(List.of("hooks.slack.com"));

        j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
        SlackTestHelpers.awaitDispatch();

        verify(0, postRequestedFor(anyUrl()));
        assertSecretNeverLogged();
        assertTrue("expected a block WARNING mentioning the host", sawBlockForHost());
    }

    @Test
    public void hostInsideAllowlistIsSent() throws Exception {
        SlackTestHelpers.config().setWebhookHostAllowlist(List.of("localhost"));

        j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
        SlackTestHelpers.awaitDispatch();

        verify(1, postRequestedFor(anyUrl()));
    }

    @Test
    public void httpsOnlyBlocksPlainHttp() throws Exception {
        SlackTestHelpers.config().setHttpsOnly(true); // allowlist empty; http://localhost is blocked

        j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
        SlackTestHelpers.awaitDispatch();

        verify(0, postRequestedFor(anyUrl()));
        assertSecretNeverLogged();
    }

    private void assertSecretNeverLogged() {
        for (LogRecord r : logging.getRecords()) {
            String rendered = r.getMessage() + " " + Arrays.toString(r.getParameters());
            assertThat(rendered, not(containsString("SUPERSECRETTOKEN")));
            assertThat(rendered, not(containsString("services")));
        }
    }

    private boolean sawBlockForHost() {
        for (LogRecord r : logging.getRecords()) {
            String rendered = r.getMessage() + " " + Arrays.toString(r.getParameters());
            if (rendered.contains("http://localhost")) {
                return true;
            }
        }
        return false;
    }
}
