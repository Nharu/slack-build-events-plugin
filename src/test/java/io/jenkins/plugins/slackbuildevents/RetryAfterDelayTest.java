package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Branch coverage for the retry-delay computation: each {@code Retry-After} shape a 429 can carry
 * is observed as the delay handed to the scheduler seam, without touching production code.
 */
public class RetryAfterDelayTest {

    private static final DateTimeFormatter RFC = DateTimeFormatter.RFC_1123_DATE_TIME;
    private static final ZonedDateTime FIXED_NOW = ZonedDateTime.of(2026, 7, 8, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final LongSupplier FIXED_CLOCK = () -> FIXED_NOW.toInstant().toEpochMilli();
    private static final Duration FUTURE_DELTA = Duration.ofSeconds(30);
    private static final Duration PAST_DELTA = Duration.ofSeconds(60);

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private TestWebhookSender sender;

    @Before
    public void setUp() throws Exception {
        sender = new TestWebhookSender();
        SlackTestHelpers.addWebhookCredential("wh", "http://example.test/hook");
        SlackTestHelpers.config().setDefaultWebhookCredentialId("wh");
        SlackTestHelpers.config().setMaxRetriesOn429(1);
        SlackTestHelpers.config().setRules(List.of(SlackTestHelpers.rule("myjob", List.of("start"))));
    }

    @Test
    public void delaySecondsNonZero() throws Exception {
        assertEquals(120000L, captureScheduledDelay(TestWebhookSender.status429RetryAfter("120")));
    }

    @Test
    public void httpDateFuture() throws Exception {
        String header = FIXED_NOW.plus(FUTURE_DELTA).format(RFC);
        // A matching format∘parse pair makes the round-trip lossless, so asserting the derived
        // FUTURE_DELTA doubles as the RFC_1123 round-trip check.
        assertEquals(FUTURE_DELTA.toMillis(), captureScheduledDelay(TestWebhookSender.status429RetryAfter(header)));
    }

    @Test
    public void negativeSecondsClampsToZero() throws Exception {
        assertEquals(0L, captureScheduledDelay(TestWebhookSender.status429RetryAfter("-5")));
    }

    @Test
    public void pastDateClampsToZero() throws Exception {
        String header = FIXED_NOW.minus(PAST_DELTA).format(RFC);
        assertEquals(0L, captureScheduledDelay(TestWebhookSender.status429RetryAfter(header)));
    }

    @Test
    public void malformedHeaderFallsBackToZero() throws Exception {
        assertEquals(0L, captureScheduledDelay(TestWebhookSender.status429RetryAfter("not-a-date")));
    }

    @Test
    public void zeroSeconds() throws Exception {
        assertEquals(0L, captureScheduledDelay(TestWebhookSender.status429RetryNow()));
    }

    @Test
    public void nullHeader() throws Exception {
        assertEquals(0L, captureScheduledDelay(TestWebhookSender.status(429)));
    }

    @Test
    public void blankHeader() throws Exception {
        assertEquals(0L, captureScheduledDelay(TestWebhookSender.status429RetryAfter("   ")));
    }

    @Test
    public void whitespaceTrimmedThenParsed() throws Exception {
        assertEquals(2000L, captureScheduledDelay(TestWebhookSender.status429RetryAfter(" 2 ")));
    }

    /**
     * Drives a first-response 429 → captures the delay the dispatcher hands the scheduler → drains
     * the held retry with a 200 so the future never leaks into {@code inFlight}, then returns the delay.
     */
    private long captureScheduledDelay(WebhookSender.Response first) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(daemonFactory("test-exec"));
        DelayCapturingScheduler scheduler = new DelayCapturingScheduler();
        NotificationDispatcher.get().installTestSeams(executor, scheduler, sender, FIXED_CLOCK);
        sender.script(first, TestWebhookSender.status(200));
        j.buildAndAssertSuccess(j.createFreeStyleProject("myjob"));
        assertTrue("retry scheduled", scheduler.scheduled.await(5, TimeUnit.SECONDS));
        assertEquals("exactly one schedule() call", 1, scheduler.scheduleCount.get());
        long delay = scheduler.capturedDelayMillis.get(); // happens-after the latch
        scheduler.captured.get().run(); // capture-and-hold drain
        NotificationDispatcher.get().awaitAllDispatched(5, TimeUnit.SECONDS);
        assertEquals("initial attempt + drained retry", 2, sender.calls.get());
        return delay;
    }

    /** Scheduler seam that records the delay and captures the retry Runnable instead of running it. */
    private static final class DelayCapturingScheduler extends ScheduledThreadPoolExecutor {
        final AtomicLong capturedDelayMillis = new AtomicLong(-1);
        final AtomicReference<Runnable> captured = new AtomicReference<>();
        final AtomicInteger scheduleCount = new AtomicInteger();
        final CountDownLatch scheduled = new CountDownLatch(1);

        DelayCapturingScheduler() {
            super(1, daemonFactory("test-delay-sched"));
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            scheduleCount.incrementAndGet();
            capturedDelayMillis.set(unit.toMillis(delay)); // record before countDown (happens-before)
            captured.set(command);
            scheduled.countDown();
            return null; // return value is ignored by the dispatcher
        }
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread t = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
