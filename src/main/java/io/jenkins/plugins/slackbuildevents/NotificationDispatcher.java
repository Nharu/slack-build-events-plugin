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
import hudson.model.Run;
import hudson.security.ACL;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import java.util.function.Supplier;
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

    /** Volume-bounds dispatch-failure WARNINGs to one line per signature per 5-minute window. */
    private final FailureLogThrottle failureLog = new FailureLogThrottle();

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
            this.failureLog.reset();
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
            // A rejection while the pool is shutting down is a teardown drop, not real saturation: stay
            // quiet (still counted in droppedCount) so a controller restart does not emit false "queue
            // saturated" WARNINGs. Only a genuinely saturated, still-running pool is signaled — routed
            // through the throttle so it collapses to one line per window (category-only signature).
            if (!ex.isShutdown()) {
                signalQueueSaturated();
            }
            finish(op);
        }
        return op.future;
    }

    private void runAttempt(Operation op) {
        NotificationContext context = op.context;
        // Hoisted so the catch can hand it to the redaction layer (the send-path secret) and so the SSRF
        // guard can run even if credential lookup threw.
        String webhookUrl = null;
        try {
            webhookUrl = lookupWebhookUrl(context.webhookCredentialId());
            if (webhookUrl == null || webhookUrl.isEmpty()) {
                // Credential absent/rotated away → a visible, rate-limited WARNING (was a silent no-op).
                signalCredentialMissing(context);
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
            RenderOutcome outcome = render(context);
            if (outcome.category() == RenderCategory.ABORTED) {
                // Interrupt surfaced during render; send nothing either way. If the pool is still running,
                // this is a genuine interrupt on a live worker → surface a rate-limited WARNING rather than
                // lose a possibly-genuine notification with no operator signal. If the pool is shutting down
                // (the executor has been torn down) stay QUIET, matching the transport sibling, so a
                // controller restart does not emit false alarms. The throttle bounds running-pool noise to
                // one line per window.
                if (executor != null) {
                    signalRenderAborted(context);
                }
                finish(op);
                return;
            }
            RenderFailureEvent failure = outcome.event();
            if (failure != null) {
                // DEGRADED/FALLBACK: signal before sending, per the failure-signal seam.
                signalRenderFailure(failure);
            }
            String text = outcome.text();
            if (text == null) {
                // Only ABORTED carries a null body, and that was handled above; defensive guard.
                finish(op);
                return;
            }
            String json = SlackMessage.build(context.channel(), context.color(), text);
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
            // Terminal HTTP outcome: 2xx silent; 429 (backpressure) QUIET; other 4xx/5xx → WARNING.
            signalHttpOutcome(context, response.statusCode());
            finish(op);
        } catch (Throwable t) {
            // Isolation: transport failures and unexpected runtime/JVM errors land here. Signal them so
            // the operator sees a WARNING (was FINE-only, invisible at default level). The finish() is in
            // a finally so a throw from the signal path never leaks the future; an Error is rethrown AFTER
            // finishing so the ThreadPoolExecutor discards and replaces the possibly-corrupted worker.
            try {
                signalTransportFailure(context, t, webhookUrl);
            } finally {
                finish(op);
            }
            if (t instanceof Error) {
                throw (Error) t;
            }
        }
    }

    // ---- Failure-signal seam: every dispatch-path failure funnels through the rate-limiting throttle.

    private void recordWarning(@NonNull String signature, @NonNull Supplier<FailureMessage> messageSupplier) {
        failureLog.recordFailure(signature, clock.getAsLong(), messageSupplier);
    }

    private void signalCredentialMissing(@NonNull NotificationContext context) {
        String signature = signatureOf(FailureCategory.CREDENTIAL_MISSING, context.webhookCredentialId());
        recordWarning(
                signature,
                () -> new FailureMessage("Slack notification " + FailureCategory.CREDENTIAL_MISSING + ": "
                        + describe(context)
                        + " — webhook credential not found (removed or rotated). No notification was sent."));
    }

    private void signalRenderFailure(@NonNull RenderFailureEvent event) {
        FailureCategory category = categoryOf(event.category);
        String signature;
        if (event.category == RenderCategory.DEGRADED) {
            // Signature: category + credentialId + templateDigest (+ failedMacroHint when present).
            signature = (event.failedMacroHint != null)
                    ? signatureOf(
                            category, event.webhookCredentialId, templateDigest(event.template), event.failedMacroHint)
                    : signatureOf(category, event.webhookCredentialId, templateDigest(event.template));
        } else {
            // FALLBACK signature: category + credentialId + templateDigest + unwrapped root cause class.
            signature = signatureOf(
                    category,
                    event.webhookCredentialId,
                    templateDigest(event.template),
                    event.rootCause.getClass().getName());
        }
        recordWarning(signature, () -> renderMessage(event));
    }

    private void signalRenderAborted(@NonNull NotificationContext context) {
        String signature = signatureOf(FailureCategory.RENDER_ABORTED, context.webhookCredentialId());
        recordWarning(
                signature,
                () -> new FailureMessage("Slack notification " + FailureCategory.RENDER_ABORTED + ": "
                        + describe(context)
                        + " — render was interrupted; the notification was not sent."));
    }

    private void signalHttpOutcome(@NonNull NotificationContext context, int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return; // success
        }
        if (statusCode == 429) {
            return; // Slack backpressure / retry-budget exhausted → QUIET (normal)
        }
        FailureCategory category;
        if (statusCode >= 500) {
            category = FailureCategory.HTTP_SERVER_ERROR;
        } else if (statusCode >= 400) {
            category = FailureCategory.HTTP_CLIENT_ERROR;
        } else {
            return; // 1xx/3xx: not a failure we signal
        }
        String signature = signatureOf(category, context.webhookCredentialId(), Integer.toString(statusCode));
        recordWarning(signature, () -> new FailureMessage("Slack notification " + category + ": " + describe(context)
                + " — Slack webhook returned HTTP " + statusCode + ". "
                + (category == FailureCategory.HTTP_SERVER_ERROR
                        ? "Slack-side error; not retried."
                        : "Check the webhook URL and configuration.")));
    }

    private void signalTransportFailure(
            @NonNull NotificationContext context, @NonNull Throwable t, @CheckForNull String webhookUrl) {
        if (t instanceof InterruptedException || hasInterruptedCause(t)) {
            // Pool shutting down mid-send → restore the flag and stay QUIET (not a real transport error).
            Thread.currentThread().interrupt();
            return;
        }
        // The only checked exception send() can throw (other than the interrupt handled above) is
        // IOException; a genuine transport failure. Everything else — a RuntimeException or a JVM Error —
        // is unexpected, so it must not claim a network failure.
        FailureCategory category =
                (t instanceof IOException) ? FailureCategory.TRANSPORT_ERROR : FailureCategory.UNEXPECTED_ERROR;
        String signature = signatureOf(category, context.webhookCredentialId(), t.getClass().getName());
        recordWarning(signature, () -> new FailureMessage("Slack notification " + category + ": " + describe(context)
                + " — " + scrubForLog(redactSecret(summarize(t), webhookUrl)) + "."
                + (category == FailureCategory.TRANSPORT_ERROR
                        ? " Network/transport failure reaching Slack."
                        : " Unexpected error while sending.")));
    }

    private void signalQueueSaturated() {
        recordWarning(
                FailureCategory.QUEUE_SATURATED.name(),
                () -> new FailureMessage("Slack notification " + FailureCategory.QUEUE_SATURATED
                        + ": dispatch queue saturated; notification dropped. The dispatch pool is at capacity."));
    }

    private static FailureMessage renderMessage(@NonNull RenderFailureEvent event) {
        FailureCategory category = categoryOf(event.category);
        String guidance = (event.category == RenderCategory.DEGRADED)
                ? "Some tokens could not be expanded; the notification was sent with those tokens left as literals."
                : "The template could not be rendered; a minimal fallback notification was sent instead.";
        // All header fields come from the event (one consistent provenance, including the build number).
        String headline = "Slack notification " + category + ": "
                + header(event.jobFullName, event.buildNumber, event.event, event.webhookCredentialId)
                + (event.failedMacroHint != null ? " token '" + event.failedMacroHint + "'" : "")
                + " — " + scrubForLog(summarize(event.rootCause)) + ". " + guidance;
        // Full stack goes in the detail, below the headline, so a piggybacked suppression count stays on
        // line 1. Rate-limited to one per window.
        return new FailureMessage(headline, stackTraceOf(event.cause));
    }

    @NonNull
    private static String stackTraceOf(@NonNull Throwable t) {
        java.io.StringWriter writer = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }

    @NonNull
    private static String describe(@NonNull NotificationContext context) {
        return header(
                context.run().getParent().getFullName(),
                context.run().getNumber(),
                context.event(),
                context.webhookCredentialId());
    }

    /** The common "job '…' build #… event … credential '…'" prefix shared by every WARNING body. */
    @NonNull
    private static String header(
            @NonNull String jobFullName,
            int buildNumber,
            @NonNull EventType event,
            @NonNull String webhookCredentialId) {
        return "job '" + jobFullName + "' build #" + buildNumber
                + " event " + event + " credential '" + webhookCredentialId + "'";
    }

    /** Maps a render disposition to its failure category — the single source for this mapping. */
    @NonNull
    private static FailureCategory categoryOf(@NonNull RenderCategory renderCategory) {
        return (renderCategory == RenderCategory.DEGRADED)
                ? FailureCategory.RENDER_DEGRADED
                : FailureCategory.RENDER_FALLBACK;
    }

    /**
     * Total, self-scrubbing one-line summary of a throwable. Guards {@code getMessage()} so it never
     * throws. Callers apply {@link #scrubForLog} — and, on the send path where a secret is in hand,
     * {@link #redactSecret} first — around this value before it reaches the log.
     */
    @NonNull
    private static String summarize(@CheckForNull Throwable t) {
        if (t == null) {
            return "unknown cause";
        }
        String message;
        try {
            message = t.getMessage();
        } catch (RuntimeException e) {
            // A hostile/broken getMessage() must not sink the whole failure signal.
            message = "<message unavailable>";
        }
        return message == null ? t.getClass().getName() : t.getClass().getName() + ": " + message;
    }

    /**
     * Replaces the exact webhook URL (which carries the Slack secret) with a placeholder, so a throwable
     * whose message embeds the raw URL — e.g. {@code URI.create}'s {@code IllegalArgumentException} on a
     * malformed URL — cannot leak the secret into the system log. Send path only; the render path never
     * holds a secret. Applied to the raw summary before {@link #scrubForLog}, so a control character in the
     * URL cannot defeat the exact-match removal.
     */
    @NonNull
    private static String redactSecret(@NonNull String message, @CheckForNull String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            return message;
        }
        return message.replace(webhookUrl, "<redacted-webhook-url>");
    }

    /**
     * Folds CR/LF/TAB and other C0 control characters to spaces, so a crafted throwable message cannot
     * forge additional log lines. Applied to the one-line {@link #summarize} output only — never to the
     * intentional multi-line stack-trace detail the render path attaches separately.
     */
    @NonNull
    private static String scrubForLog(@NonNull String value) {
        StringBuilder scrubbed = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            scrubbed.append(c < 0x20 ? ' ' : c);
        }
        return scrubbed.toString();
    }

    @NonNull
    private static String signatureOf(@NonNull FailureCategory category, @NonNull String... parts) {
        StringBuilder signature = new StringBuilder(category.name());
        for (String part : parts) {
            signature.append('|').append(part == null ? "" : part);
        }
        return signature.toString();
    }

    /**
     * Fixed-length stable hash of the raw template, so a template-centric signature stays deterministic
     * and reproducible without embedding the whole template in the key.
     */
    @NonNull
    private static String templateDigest(@NonNull String template) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(template.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated on every JRE; degrade to a stable-enough hashCode hex if ever absent.
            return Integer.toHexString(template.hashCode());
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

    /**
     * Two-pass render contract. Never sends and never returns the raw template; instead classifies
     * the outcome so the caller can send the right body and raise the right signal.
     *
     * <ul>
     *   <li>Pass 1 (strict, {@code throwException=true}) succeeds → {@code CLEAN}.
     *   <li>Pass 1 throws, Pass 2 (lenient, {@code throwException=false}) succeeds → {@code DEGRADED}:
     *       only the failed tokens survive as core-produced literals; the flat output is whole-escaped
     *       so a hostile macro-exception message cannot inject mrkdwn link markup.
     *   <li>Both passes throw → {@code FALLBACK}: a minimal, macro-independent marked message.
     *   <li>An interrupt (raw or wrapped) surfaces on either pass → {@code ABORTED}: restore the flag,
     *       send nothing, signal nothing (the pool is shutting down).
     * </ul>
     *
     * <p>Package-private so a unit test can drive it directly and observe the worker-thread interrupt
     * flag deterministically (the async harness cannot).
     */
    @NonNull
    @SuppressFBWarnings(
            value = "REC_CATCH_EXCEPTION",
            justification = "The broad catch is intentional: any throwable from a pass — checked "
                    + "(MacroEvaluationException/IOException/InterruptedException) or an unchecked macro error — "
                    + "must be routed through the two-pass fallback contract, never propagated.")
    RenderOutcome render(@NonNull NotificationContext context) {
        // Install the start-time branch snapshot for the duration of this render only. Cleared on
        // every return path in the finally, so it never leaks to the next notification on this pooled
        // worker or across a retry hand-off.
        GitMacroSupport.setStartBranchHint(context.branchHint());
        try {
            String clean = TokenMacro.expandAll(context.run(), null, context.listener(), context.template());
            return RenderOutcome.clean(clean);
        } catch (Exception pass1) {
            if (abortIfInterrupted(pass1)) {
                return RenderOutcome.aborted();
            }
            try {
                String lenient = TokenMacro.expandAll(
                        context.run(), null, context.listener(), context.template(), false, null);
                // The lenient output is a single flat string with token boundaries lost, so escape all
                // of it: a registered macro's exception message lands verbatim in the core-produced
                // [Error replacing 'NAME' - <msg>] literal and could otherwise inject <url|label> markup.
                String degraded = SlackText.escape(lenient);
                return RenderOutcome.degraded(degraded, failureEvent(RenderCategory.DEGRADED, context, pass1));
            } catch (Exception pass2) {
                if (abortIfInterrupted(pass2)) {
                    return RenderOutcome.aborted();
                }
                return RenderOutcome.fallback(
                        buildFallbackText(context.run()), failureEvent(RenderCategory.FALLBACK, context, pass2));
            }
        } finally {
            GitMacroSupport.clearStartBranchHint();
        }
    }

    /**
     * Detects an interrupt surfaced during render — either a raw {@link InterruptedException} (from
     * {@code run.getEnvironment()} before parse) or one wrapped in a {@code MacroEvaluationException}
     * (from a macro's {@code evaluate()}) — via the cause chain and the thread flag. When found,
     * restores the interrupt flag so the dispatch pool's cooperative-cancellation contract holds, and
     * signals an abort.
     */
    private static boolean abortIfInterrupted(@NonNull Throwable pass) {
        if (Thread.currentThread().isInterrupted() || hasInterruptedCause(pass)) {
            Thread.currentThread().interrupt();
            return true;
        }
        return false;
    }

    private static boolean hasInterruptedCause(@NonNull Throwable throwable) {
        Throwable current = throwable;
        int guard = 0;
        while (current != null && guard++ < RenderFailureEvent.MAX_CAUSE_CHAIN_DEPTH) {
            if (current instanceof InterruptedException) {
                return true;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return false;
    }

    @NonNull
    private static RenderFailureEvent failureEvent(
            @NonNull RenderCategory category, @NonNull NotificationContext context, @NonNull Throwable cause) {
        Throwable rootCause = RenderFailureEvent.unwrap(cause);
        // The hint only sharpens the DEGRADED suppression signature, and it must be parsed from the
        // unwrapped root cause: the outer wrapper's message is the constant "Error processing tokens",
        // which carries no token name, so parsing the wrapper would yield null on the dominant
        // unrecognized-macro path. FALLBACK's root cause is an arbitrary macro throwable, so no hint.
        String hint = (category == RenderCategory.DEGRADED) ? parseFailedMacroHint(rootCause) : null;
        return new RenderFailureEvent(
                category,
                cause,
                rootCause,
                context.run().getParent().getFullName(),
                context.run().getNumber(),
                context.event(),
                context.webhookCredentialId(),
                context.template(),
                hint);
    }

    /**
     * Best-effort extraction of the failing token's name from the strict-pass exception message
     * (e.g. token-macro's {@code Unrecognized macro 'NAME' in '...'}). Returns {@code null} when no
     * quoted name is present; the signature key falls back to the template digest, so this is never
     * load-bearing for signature stability.
     */
    @CheckForNull
    private static String parseFailedMacroHint(@NonNull Throwable cause) {
        String message = cause.getMessage();
        if (message == null) {
            return null;
        }
        int open = message.indexOf('\'');
        if (open < 0) {
            return null;
        }
        int close = message.indexOf('\'', open + 1);
        if (close <= open + 1) {
            return null;
        }
        return message.substring(open + 1, close);
    }

    /**
     * Minimal, macro-independent fallback body built directly from the run (token-macro has already
     * failed). Language-neutral, consistent with {@link DefaultTemplates}. Only the job full name is
     * attacker/admin-influenced, so it is mrkdwn-escaped; the URL is left unescaped because
     * {@code getAbsoluteUrl()} percent-encodes the job-name segment. When no absolute URL is available
     * (root URL unset), the link line is omitted entirely.
     */
    @NonNull
    @SuppressWarnings("deprecation") // getAbsoluteUrl: root-URL dependence is intentional and guarded
    private static String buildFallbackText(@NonNull Run<?, ?> run) {
        StringBuilder body = new StringBuilder();
        body.append("⚠️ *Slack notification template failed to render*\n");
        body.append("*Job*: ").append(SlackText.escape(run.getParent().getFullName())).append('\n');
        body.append("*Build*: #").append(run.getNumber());
        String url;
        try {
            url = run.getAbsoluteUrl();
        } catch (RuntimeException e) {
            // Jenkins root URL not configured (or any other failure) → no link line.
            url = "";
        }
        if (!url.isEmpty()) {
            body.append("\n<").append(url).append("|Open build>");
        }
        return body.toString();
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
