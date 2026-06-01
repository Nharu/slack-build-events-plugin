package io.jenkins.plugins.slackbuildevents;

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
    private volatile int defaultStatus = 200;
    private volatile CountDownLatch gate;

    /** Queues responses returned (in order) by successive {@link #send} calls. */
    void script(Response... responses) {
        Collections.addAll(scripted, responses);
    }

    /** Convenience for a 429 with a {@code Retry-After} of {@code 0} (immediate retry). */
    static Response status429RetryNow() {
        return new Response(429, "0");
    }

    static Response status(int code) {
        return new Response(code, null);
    }

    void blockOn(CountDownLatch gate) {
        this.gate = gate;
    }

    @Override
    Response send(String url, String jsonBody) throws IOException, InterruptedException {
        calls.incrementAndGet();
        urls.add(url);
        bodies.add(jsonBody);
        CountDownLatch g = gate;
        if (g != null) {
            g.await();
        }
        Response next = scripted.poll();
        return next != null ? next : new Response(defaultStatus, null);
    }
}
