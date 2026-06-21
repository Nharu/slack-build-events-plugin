package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Drop accounting + shutdown-race null-window regression: a notification dropped because a
 * pool is unavailable (saturated queue, or a pool torn down at shutdown) must still complete
 * its future — never leaking into {@code inFlight} and hanging the await barrier — and is
 * counted exactly once by {@code droppedCount()}.
 */
public class RetryCapAndDropTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private TestWebhookSender sender;

    @Before
    public void setUp() throws Exception {
        sender = new TestWebhookSender();
        SlackTestHelpers.addWebhookCredential("wh", "http://example.test/hook");
    }

    @Test
    public void saturatedExecutorDropsWithoutLeak() throws Exception {
        // A dead (already shut-down) executor rejects every submit → a pool-unavailability drop.
        ExecutorService deadExecutor = Executors.newSingleThreadExecutor(daemonFactory("dead-exec"));
        deadExecutor.shutdownNow();
        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(daemonFactory("test-sched"));
        NotificationDispatcher.get().installTestSeams(deadExecutor, scheduler, sender, System::currentTimeMillis);
        SlackTestHelpers.config().setDefaultWebhookCredentialId("wh");
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("myjob", List.of("start"))));

        j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));

        // The barrier must return (no leaked future); the drop counted once; send never reached.
        NotificationDispatcher.get().awaitAllDispatched(5, TimeUnit.SECONDS);
        assertEquals(1, NotificationDispatcher.get().droppedCount());
        assertEquals(0, sender.calls.get());
    }

    @Test
    public void retryFiringAfterShutdownDropsWithoutLeak() throws Exception {
        // Manual scheduler seam: capture the resubmit Runnable instead of running it, so we can
        // fire it *after* the pools are torn down — the @Terminator stopAll() race.
        ExecutorService executor = Executors.newSingleThreadExecutor(daemonFactory("test-exec"));
        CapturingScheduler scheduler = new CapturingScheduler();
        NotificationDispatcher.get().installTestSeams(executor, scheduler, sender, System::currentTimeMillis);
        SlackTestHelpers.config().setMaxRetriesOn429(1);
        sender.script(TestWebhookSender.status429RetryNow(), TestWebhookSender.status(200));
        SlackTestHelpers.config().setDefaultWebhookCredentialId("wh");
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("myjob", List.of("start"))));

        j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
        // First attempt got 429 → a retry was scheduled (captured here, not run).
        assertTrue("a retry was scheduled", scheduler.scheduled.await(5, TimeUnit.SECONDS));

        // The shutdown race: pools are nulled while the retry sits pending.
        NotificationDispatcher.stopAll();
        // Now fire the captured retry into the torn-down dispatcher.
        scheduler.captured.get().run();

        // Pre-fix: resubmit hit executor==null → NPE → finish() skipped → future leaked → barrier hung.
        // Post-fix: the null-pool guard drops and finishes, so the barrier returns and the drop counts once.
        NotificationDispatcher.get().awaitAllDispatched(5, TimeUnit.SECONDS);
        assertEquals(1, NotificationDispatcher.get().droppedCount());
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread t = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /** Scheduler seam that captures the scheduled Runnable instead of running it. */
    private static final class CapturingScheduler extends ScheduledThreadPoolExecutor {
        final AtomicReference<Runnable> captured = new AtomicReference<>();
        final CountDownLatch scheduled = new CountDownLatch(1);

        CapturingScheduler() {
            super(1, daemonFactory("test-capture-sched"));
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            captured.set(command);
            scheduled.countDown();
            return null; // return value is ignored by the dispatcher
        }
    }
}
