package io.jenkins.plugins.slackbuildevents;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
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
}
