package io.jenkins.plugins.slackbuildevents;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/** Real transport coverage: the production {@link WebhookSender} POSTs over HTTP and honors Retry-After. */
public class WebhookHttpTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Rule
    public WireMockRule wireMock = new WireMockRule(options().dynamicPort());

    @Before
    public void setUp() throws Exception {
        SlackTestHelpers.installSeams(new WebhookSender());
        SlackTestHelpers.addWebhookCredential("wh", wireMock.baseUrl());
        SlackTestHelpers.config().setDefaultWebhookCredentialId("wh");
    }

    @Test
    public void startAndSuccessAreDeliveredOverHttp() throws Exception {
        stubFor(post(anyUrl()).willReturn(aResponse().withStatus(200)));
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("myjob", List.of("start", "success"))));

        j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
        SlackTestHelpers.awaitDispatch();

        verify(2, postRequestedFor(anyUrl()));
    }

    @Test
    public void retryAfterIsHonoredOverHttp() throws Exception {
        stubFor(post(anyUrl())
                .inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
                .willSetStateTo("recovered"));
        stubFor(post(anyUrl())
                .inScenario("retry")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)));

        SlackTestHelpers.config().setMaxRetriesOn429(1);
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("myjob", List.of("start"))));

        j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
        SlackTestHelpers.awaitDispatch();

        verify(2, postRequestedFor(anyUrl()));
    }

    @Test
    public void redirectResponseIsNotFollowed() throws Exception {
        // Redirect.NEVER linchpin: a 302 must NOT trigger a second request to the redirect target,
        // which could reach an internal host and bypass the webhook host allowlist (SSRF). No source
        // change — this pins the default HttpClient policy end-to-end so a future NORMAL/ALWAYS regresses.
        stubFor(post(urlEqualTo("/")).willReturn(aResponse().withStatus(302).withHeader("Location", "/internal")));
        stubFor(any(urlEqualTo("/internal")).willReturn(aResponse().withStatus(200)));
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("myjob", List.of("start"))));

        j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
        SlackTestHelpers.awaitDispatch();

        verify(1, postRequestedFor(urlEqualTo("/")));
        verify(0, anyRequestedFor(urlEqualTo("/internal")));
    }
}
