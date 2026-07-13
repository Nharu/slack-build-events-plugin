package io.jenkins.plugins.slackbuildevents;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
 * so the {@link #awaitAllDispatched} test barrier waits for the in-flight work captured
 * at the moment it is called; work enqueued after that snapshot is not awaited.
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

    /** Guarded by {@code this}: once {@link #shutdownPools()} has run, refuse to recreate pools. */
    private boolean stopped;

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
        // Drain any running pools first (takes the lock internally, blocks outside it),
        // then reinstall the seams and re-arm under a small lock so a concurrent
        // dispatch/ensureStarted sees a consistent state.
        shutdownPools();
        synchronized (this) {
            this.executor = executor;
            this.scheduler = scheduler;
            this.sender = sender;
            this.clock = clock;
            this.stopped = false;
            this.pendingRetries.set(0);
            this.droppedCount.set(0);
            this.inFlight.clear();
        }
    }

    private synchronized void ensureStarted() {
        if (stopped) {
            // Shutdown has run; do not recreate pools (e.g. a late dispatch during teardown).
            return;
        }
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
        ExecutorService ex = executor;
        if (ex == null) {
            // Pool torn down between ensureStarted() and here (shutdown race) → drop
            // without leaking the future into inFlight forever.
            droppedCount.incrementAndGet();
            finish(op);
            return op.future;
        }
        try {
            ex.execute(() -> runAttempt(op));
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
            // SSRF hardening at the authoritative send point (a stub sender would bypass a
            // WebhookSender-level check). null config → unrestricted, mirroring currentMaxRetries.
            SlackNotifierGlobalConfig config = SlackNotifierGlobalConfig.get();
            if (config != null
                    && !WebhookUrlPolicy.isAllowed(webhookUrl, config.isHttpsOnly(), config.getWebhookHostAllowlist())) {
                // Log scheme+host only — never the secret path/token — then terminate best-effort.
                LOGGER.log(
                        Level.WARNING,
                        "Slack notification blocked by webhook URL policy: {0}",
                        WebhookUrlPolicy.describeSafely(webhookUrl));
                finish(op);
                return;
            }
            String text = render(op.context);
            String json = SlackMessage.build(op.context.channel(), op.context.color(), text);
            WebhookSender.Response response = sender.send(webhookUrl, json);

            if (response.statusCode() == 429 && op.attemptsLeft > 0) {
                // Reserve a retry slot atomically, then roll back if over the global cap.
                // Doing the check-and-increment here (not in scheduleRetry) makes the cap
                // test-then-act a single atomic step under concurrent retries.
                if (pendingRetries.incrementAndGet() <= MAX_PENDING_RETRIES) {
                    scheduleRetry(op, retryDelayMillis(response.retryAfter()));
                    return;
                }
                pendingRetries.decrementAndGet();
                finish(op);
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

    @SuppressFBWarnings(
            value = "VO_VOLATILE_INCREMENT",
            justification = "attemptsLeft is mutated by at most one dispatch thread at a time for a given "
                    + "operation (never concurrently); the volatile is for cross-thread visibility across the "
                    + "retry handoff, not for atomicity, so the non-atomic decrement is intentional and safe.")
    private void scheduleRetry(Operation op, long delayMillis) {
        // The retry slot was already reserved (pendingRetries incremented) in runAttempt.
        op.attemptsLeft--;
        ScheduledExecutorService sch = scheduler;
        if (sch == null) {
            // Pool torn down (shutdown race) → release the reserved slot and drop.
            pendingRetries.decrementAndGet();
            droppedCount.incrementAndGet();
            finish(op);
            return;
        }
        try {
            sch.schedule(() -> resubmit(op), delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            pendingRetries.decrementAndGet();
            droppedCount.incrementAndGet();
            finish(op);
        }
    }

    /** Fired by the scheduler thread when a retry's delay elapses; resubmits onto the dispatch pool. */
    private void resubmit(Operation op) {
        pendingRetries.decrementAndGet();
        ExecutorService ex = executor;
        try {
            if (ex == null) {
                // Pool torn down between scheduling and firing → drop without leaking.
                droppedCount.incrementAndGet();
                finish(op);
                return;
            }
            ex.execute(() -> runAttempt(op));
        } catch (Throwable t) {
            // Widened from RejectedExecutionException: any failure on this path must still
            // finish(op), or the future leaks into inFlight and the await barrier hangs.
            droppedCount.incrementAndGet();
            finish(op);
        }
    }

    @NonNull
    private String render(@NonNull NotificationContext context) {
        // Install the start-time branch snapshot for the duration of this render only.
        GitMacroSupport.setStartBranchHint(context.branchHint());
        try {
            // Single pass: substituted values are not re-scanned, so user-controlled env
            // cannot inject further macros.
            return TokenMacro.expandAll(context.run(), null, context.listener(), context.template());
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Template expansion failed; sending raw template", e);
            return context.template();
        } finally {
            // Clear on EVERY exit path (including the raw-template fallback above) so the hint never
            // leaks to the next notification rendered on this pooled worker or across a retry hand-off.
            GitMacroSupport.clearStartBranchHint();
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
        return Math.min(value, SlackNotifierGlobalConfig.MAX_RETRIES_LIMIT);
    }

    private void finish(Operation op) {
        inFlight.remove(op.future);
        op.future.complete(null);
    }

    /**
     * Test/diagnostic: number of notifications dropped due to pool unavailability — the
     * dispatch queue being saturated or a pool torn down at shutdown. Cap-reached and
     * retry-budget exhaustion are normal terminal outcomes and are NOT counted here.
     */
    long droppedCount() {
        return droppedCount.get();
    }

    /**
     * Test barrier: waits for the in-flight notifications (including any already-scheduled
     * retries) captured at call time. Work enqueued after this snapshot is not awaited.
     */
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
        ExecutorService ex;
        ScheduledExecutorService sch;
        synchronized (this) {
            ex = executor;
            sch = scheduler;
            executor = null;
            scheduler = null;
            stopped = true;
        }
        // Drain outside the lock (awaitTermination blocks). Scheduler first: a retry
        // scheduled during the shutdown window is then not resubmitted onto an executor
        // that is already closing, avoiding an otherwise-avoidable drop.
        shutdown(sch);
        shutdown(ex);
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

    /** Per-notification mutable state handed between dispatch/scheduler threads across retries. */
    private static final class Operation {
        private final NotificationContext context;
        private final CompletableFuture<Void> future = new CompletableFuture<>();
        // Handed between executor pool threads across a retry. There is never concurrent
        // mutation (one attempt runs at a time), so the non-atomic {@code --} is fine;
        // volatile documents and guarantees cross-thread visibility of the handoff, which
        // the executor submit's happens-before already establishes.
        private volatile int attemptsLeft;

        Operation(NotificationContext context, int attemptsLeft) {
            this.context = context;
            this.attemptsLeft = attemptsLeft;
        }
    }
}
