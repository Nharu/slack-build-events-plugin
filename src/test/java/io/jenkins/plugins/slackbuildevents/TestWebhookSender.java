package io.jenkins.plugins.slackbuildevents;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test double for {@link WebhookSender} (an explicit injection seam): records every
 * call's URL and body, returns scripted responses, and can block on a latch to prove
 * dispatch is off the build thread.
 */
class TestWebhookSender extends WebhookSender {

    final List<String> urls = Collections.synchronizedList(new ArrayList<>());
    final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger calls = new AtomicInteger();

    private final Queue<Response> scripted = new ConcurrentLinkedQueue<>();
    private volatile Throwable toThrow;
    private volatile int defaultStatus = 200;
    private volatile CountDownLatch gate;
    private volatile CountDownLatch entered; // counted down when send enters (just before it blocks)
    private volatile CountDownLatch completed; // counted down when send fully returns (after release)

    /** Queues responses returned (in order) by successive {@link #send} calls. */
    void script(Response... responses) {
        Collections.addAll(scripted, responses);
    }

    /** Convenience for a 429 with a {@code Retry-After} of {@code 0} (immediate retry). */
    static Response status429RetryNow() {
        return new Response(429, "0");
    }

    /** A 429 carrying an arbitrary Retry-After header (null ⇒ header absent). */
    static Response status429RetryAfter(@CheckForNull String retryAfter) {
        return new Response(429, retryAfter);
    }

    static Response status(int code) {
        return new Response(code, null);
    }

    void blockOn(CountDownLatch gate) {
        this.gate = gate;
    }

    /** Signals that send has entered (and is about to block): proves the work is in-flight. */
    void signalEntryOn(CountDownLatch entered) {
        this.entered = entered;
    }

    /** Signals that send has fully finished (after the block is released). */
    void signalCompletionOn(CountDownLatch completed) {
        this.completed = completed;
    }

    /** Makes every subsequent {@link #send} record the call and then throw {@code t}. */
    void throwOn(Throwable t) {
        this.toThrow = t;
    }

    @Override
    Response send(String url, String jsonBody) throws IOException, InterruptedException {
        calls.incrementAndGet();
        urls.add(url);
        bodies.add(jsonBody);
        rethrowIfScripted();
        CountDownLatch e = entered;
        if (e != null) {
            e.countDown();
        }
        CountDownLatch g = gate;
        if (g != null) {
            g.await();
        }
        Response next = scripted.poll();
        Response response = next != null ? next : new Response(defaultStatus, null);
        CountDownLatch c = completed;
        if (c != null) {
            c.countDown();
        }
        return response;
    }

    private void rethrowIfScripted() throws IOException, InterruptedException {
        Throwable t = toThrow;
        if (t == null) {
            return;
        }
        if (t instanceof IOException) {
            throw (IOException) t;
        }
        if (t instanceof InterruptedException) {
            throw (InterruptedException) t;
        }
        if (t instanceof RuntimeException) {
            throw (RuntimeException) t;
        }
        if (t instanceof Error) {
            throw (Error) t;
        }
        throw new IllegalArgumentException("send() cannot throw " + t.getClass().getName(), t);
    }
}
