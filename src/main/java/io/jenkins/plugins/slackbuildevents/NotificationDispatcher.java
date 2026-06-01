package io.jenkins.plugins.slackbuildevents;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.init.Terminator;
import hudson.security.ACL;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;

/**
 * Owns the asynchronous, best-effort notification pipeline: a bounded dispatch pool
 * for rendering + HTTP POST, plus a scheduler for non-blocking 429 retries. A single
 * {@code @Extension} instance per plugin; pools are lazily created on first use and
 * torn down by {@link #stopAll()} at shutdown.
 *
 * <p>Build/event threads only ever {@link #dispatch} (cheap enqueue); Slack latency or
 * outages never block a build. Every code path completes the per-notification future,
 * so the {@link #awaitAllDispatched} test barrier is race-free.
 */
@Extension
public class NotificationDispatcher {

    private static final Logger LOGGER = Logger.getLogger(NotificationDispatcher.class.getName());

    private static final int CORE_POOL = 2;
    private static final int MAX_POOL = 4;
    private static final int QUEUE_CAPACITY = 1000;
    /** Global cap on outstanding 429 retries (memory guard); consistent with the queue size. */
    private static final int MAX_PENDING_RETRIES = 500;
    private static final long AWAIT_TERMINATION_SECONDS = 5L;

    private volatile ExecutorService executor;
    private volatile ScheduledExecutorService scheduler;
    private volatile WebhookSender sender;
    private volatile LongSupplier clock = System::currentTimeMillis;

    private final AtomicInteger pendingRetries = new AtomicInteger();
    private final AtomicLong droppedCount = new AtomicLong();
    private final Set<CompletableFuture<Void>> inFlight = ConcurrentHashMap.newKeySet();

    @NonNull
    static NotificationDispatcher get() {
        return ExtensionList.lookupSingleton(NotificationDispatcher.class);
    }

    /** Replaces the runtime seams for tests; shuts down anything already started. */
    void installTestSeams(
            @NonNull ExecutorService executor,
            @NonNull ScheduledExecutorService scheduler,
            @NonNull WebhookSender sender,
            @NonNull LongSupplier clock) {
        shutdownPools();
        this.executor = executor;
        this.scheduler = scheduler;
        this.sender = sender;
        this.clock = clock;
        this.pendingRetries.set(0);
        this.droppedCount.set(0);
        this.inFlight.clear();
    }

    private synchronized void ensureStarted() {
        if (executor == null) {
            executor = new ThreadPoolExecutor(
                    CORE_POOL,
                    MAX_POOL,
                    60L,
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                    daemonThreadFactory());
        }
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
        }
        if (sender == null) {
            sender = new WebhookSender();
        }
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread t = new Thread(runnable, "slack-build-events-dispatch-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * Enqueues one notification. Returns a future that completes when the notification
     * is terminally done (sent, dropped, or failed) — including after any 429 retries.
     */
    @NonNull
    CompletableFuture<Void> dispatch(@NonNull NotificationContext context) {
        ensureStarted();
        int maxRetries = clampRetries(currentMaxRetries());
        Operation op = new Operation(context, maxRetries);
        inFlight.add(op.future);
        try {
            executor.execute(() -> runAttempt(op));
        } catch (RejectedExecutionException e) {
            droppedCount.incrementAndGet();
            LOGGER.log(Level.WARNING, "Slack notification dropped: dispatch queue saturated");
            finish(op);
        }
        return op.future;
    }

    private void runAttempt(Operation op) {
        try {
            String webhookUrl = lookupWebhookUrl(op.context.webhookCredentialId());
            if (webhookUrl == null || webhookUrl.isEmpty()) {
                // Credential absent/rotated away → silent no-op.
                finish(op);
                return;
            }
            String text = render(op.context);
            String json = SlackMessage.build(op.context.channel(), op.context.color(), text);
            WebhookSender.Response response = sender.send(webhookUrl, json);

            if (response.statusCode() == 429 && op.attemptsLeft > 0 && pendingRetries.get() < MAX_PENDING_RETRIES) {
                scheduleRetry(op, retryDelayMillis(response.retryAfter()));
                return;
            }
            // 2xx / other 4xx / 5xx → terminal best-effort (no further retry).
            finish(op);
        } catch (Throwable t) {
            // Isolation: success, 500, timeout, missing-credential no-op all land here safely.
            LOGGER.log(Level.FINE, "Slack notification attempt failed", t);
            finish(op);
        }
    }

    private void scheduleRetry(Operation op, long delayMillis) {
        op.attemptsLeft--;
        pendingRetries.incrementAndGet();
        try {
            scheduler.schedule(
                    () -> {
                        pendingRetries.decrementAndGet();
                        try {
                            executor.execute(() -> runAttempt(op));
                        } catch (RejectedExecutionException e) {
                            droppedCount.incrementAndGet();
                            finish(op);
                        }
                    },
                    delayMillis,
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            pendingRetries.decrementAndGet();
            droppedCount.incrementAndGet();
            finish(op);
        }
    }

    @NonNull
    private String render(@NonNull NotificationContext context) {
        try {
            // Single pass: substituted values are not re-scanned, so user-controlled env
            // cannot inject further macros.
            return TokenMacro.expandAll(context.run(), null, context.listener(), context.template());
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Template expansion failed; sending raw template", e);
            return context.template();
        }
    }

    @CheckForNull
    private String lookupWebhookUrl(@NonNull String credentialId) {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return null;
        }
        List<StringCredentials> all = CredentialsProvider.lookupCredentialsInItemGroup(
                StringCredentials.class,
                jenkins,
                ACL.SYSTEM2,
                Collections.<DomainRequirement>emptyList());
        StringCredentials match = CredentialsMatchers.firstOrNull(all, CredentialsMatchers.withId(credentialId));
        if (match == null) {
            return null;
        }
        return match.getSecret().getPlainText();
    }

    private long retryDelayMillis(@CheckForNull String retryAfterHeader) {
        if (retryAfterHeader == null || retryAfterHeader.isBlank()) {
            return 0L;
        }
        String header = retryAfterHeader.trim();
        try {
            return Math.max(0L, Long.parseLong(header) * 1000L);
        } catch (NumberFormatException ignored) {
            // not a delay-seconds value; try HTTP-date.
        }
        try {
            long epochMillis = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli();
            return Math.max(0L, epochMillis - clock.getAsLong());
        } catch (DateTimeParseException ignored) {
            return 0L;
        }
    }

    private int currentMaxRetries() {
        SlackNotifierGlobalConfig config = SlackNotifierGlobalConfig.get();
        return config == null ? 0 : config.getMaxRetriesOn429();
    }

    private static int clampRetries(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 5);
    }

    private void finish(Operation op) {
        inFlight.remove(op.future);
        op.future.complete(null);
    }

    /** Test/diagnostic: number of notifications dropped (queue saturation / retry exhaustion). */
    long droppedCount() {
        return droppedCount.get();
    }

    /** Test barrier: waits until all in-flight notifications (including scheduled retries) finish. */
    void awaitAllDispatched(long timeout, @NonNull TimeUnit unit)
            throws InterruptedException, TimeoutException {
        CompletableFuture<?>[] snapshot = inFlight.toArray(new CompletableFuture<?>[0]);
        try {
            CompletableFuture.allOf(snapshot).get(timeout, unit);
        } catch (java.util.concurrent.ExecutionException e) {
            // futures never complete exceptionally (best-effort), but be defensive.
            LOGGER.log(Level.FINE, "awaitAllDispatched saw an exceptional future", e);
        }
    }

    @Terminator
    public static void stopAll() {
        for (NotificationDispatcher dispatcher : ExtensionList.lookup(NotificationDispatcher.class)) {
            dispatcher.shutdownPools();
        }
    }

    private void shutdownPools() {
        shutdown(executor);
        shutdown(scheduler);
        executor = null;
        scheduler = null;
    }

    private static void shutdown(@CheckForNull ExecutorService service) {
        if (service == null) {
            return;
        }
        service.shutdown();
        try {
            if (!service.awaitTermination(AWAIT_TERMINATION_SECONDS, TimeUnit.SECONDS)) {
                service.shutdownNow();
            }
        } catch (InterruptedException e) {
            service.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Per-notification mutable state; {@code attemptsLeft} is only touched on dispatch threads. */
    private static final class Operation {
        private final NotificationContext context;
        private final CompletableFuture<Void> future = new CompletableFuture<>();
        private int attemptsLeft;

        Operation(NotificationContext context, int attemptsLeft) {
            this.context = context;
            this.attemptsLeft = attemptsLeft;
        }
    }
}
