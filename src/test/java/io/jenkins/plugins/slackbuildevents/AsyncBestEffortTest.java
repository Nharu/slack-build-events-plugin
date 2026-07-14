package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import hudson.model.FreeStyleProject;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/** (e) asynchronous, best-effort dispatch: Slack latency/errors never block or fail a build. */
public class AsyncBestEffortTest {

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
    public void buildCompletesWhileWebhookIsBlocked() throws Exception {
        // If dispatch ran on the build thread, this latch would deadlock the build.
        CountDownLatch gate = new CountDownLatch(1);
        sender.blockOn(gate);
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("myjob", List.of("start"))));

        FreeStyleProject p = j.createFreeStyleProject("myjob");
        // Returns successfully even though the webhook send is still blocked.
        j.buildAndAssertSuccess(p);

        gate.countDown();
        SlackTestHelpers.awaitDispatch();
        assertEquals(1, sender.calls.get());
    }

    @Test
    public void webhookErrorDoesNotFailBuild() throws Exception {
        sender.script(TestWebhookSender.status(500), TestWebhookSender.status(500));
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("myjob", List.of("start", "success"))));

        FreeStyleProject p = j.createFreeStyleProject("myjob");
        j.buildAndAssertSuccess(p);
        SlackTestHelpers.awaitDispatch();

        // 500은 재시도 없는 종단 처리이므로, 배리어 통과 후 정확히 두 번의 send가 시도되었다.
        assertEquals("both events still attempted despite 500s", 2, sender.calls.get());
    }

    @Test
    public void awaitBarrierIncludesPendingWork() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        sender.blockOn(gate);
        sender.signalEntryOn(entered);
        sender.signalCompletionOn(completed);
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("myjob", List.of("start"))));
        j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));

        // send가 게이트 앞에 멈춰 있다: future는 inFlight에 있으나 아직 미완 — 배리어가 통과해야 할 바로 그 상태.
        assertTrue("send entered", entered.await(10, TimeUnit.SECONDS));
        assertEquals("send has not completed yet", 1, completed.getCount());

        // 다른 스레드에서 해제(sleep 없음). 배리어는 해제된 send가 끝날 때까지 반환하면 안 된다.
        Thread releaser = new Thread(gate::countDown, "gate-releaser");
        releaser.start();

        SlackTestHelpers.awaitDispatch();

        // 배리어는 아직 대기 중이던 send가 완료된 뒤에야 반환했다 — 여기서 추가 대기는 하지 않는다.
        assertEquals("barrier waited for the blocked send to finish", 0, completed.getCount());
        releaser.join();
        assertEquals(1, sender.calls.get());
    }
}
