package io.jenkins.plugins.slackbuildevents;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.util.Secret;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;

/** Shared setup for the listener/dispatcher tests. */
final class SlackTestHelpers {

    private SlackTestHelpers() {}

    static void addWebhookCredential(String id, String url) throws Exception {
        StringCredentials credential =
                new StringCredentialsImpl(CredentialsScope.SYSTEM, id, "test webhook", Secret.fromString(url));
        SystemCredentialsProvider provider = SystemCredentialsProvider.getInstance();
        provider.getCredentials().add(credential);
        provider.save();
    }

    /** Installs deterministic seams (daemon pools so the JVM never hangs at exit). */
    static void installSeams(WebhookSender sender) {
        ExecutorService executor = Executors.newFixedThreadPool(2, daemonFactory("test-dispatch"));
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory("test-sched"));
        NotificationDispatcher.get().installTestSeams(executor, scheduler, sender, System::currentTimeMillis);
    }

    static void awaitDispatch() throws InterruptedException, TimeoutException {
        NotificationDispatcher.get().awaitAllDispatched(15, TimeUnit.SECONDS);
    }

    static SlackNotifierGlobalConfig config() {
        SlackNotifierGlobalConfig config = SlackNotifierGlobalConfig.get();
        if (config == null) {
            throw new IllegalStateException("global config not available");
        }
        return config;
    }

    static NotificationRule rule(String pattern, List<String> events) {
        NotificationRule rule = new NotificationRule(pattern);
        rule.setNotifyStart(events.contains("start"));
        rule.setNotifySuccess(events.contains("success"));
        rule.setNotifyFailure(events.contains("failure"));
        rule.setNotifyUnstable(events.contains("unstable"));
        rule.setNotifyAborted(events.contains("aborted"));
        rule.setNotifyNotBuilt(events.contains("notBuilt"));
        return rule;
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
