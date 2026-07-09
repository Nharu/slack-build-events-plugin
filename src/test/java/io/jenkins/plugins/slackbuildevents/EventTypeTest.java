package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import hudson.model.Result;
import org.junit.Test;

/** Unit coverage for {@link EventType#fromResult} — every Result mapping plus the null fallback. */
public class EventTypeTest {

    @Test
    public void successMapsToSuccessEvent() {
        assertEquals(EventType.SUCCESS, EventType.fromResult(Result.SUCCESS));
    }

    @Test
    public void failureMapsToFailureEvent() {
        assertEquals(EventType.FAILURE, EventType.fromResult(Result.FAILURE));
    }

    @Test
    public void unstableMapsToUnstableEvent() {
        assertEquals(EventType.UNSTABLE, EventType.fromResult(Result.UNSTABLE));
    }

    @Test
    public void abortedMapsToAbortedEvent() {
        assertEquals(EventType.ABORTED, EventType.fromResult(Result.ABORTED));
    }

    @Test
    public void notBuiltMapsToNotBuiltEvent() {
        assertEquals(EventType.NOT_BUILT, EventType.fromResult(Result.NOT_BUILT));
    }

    @Test
    public void nullResultMapsToNull() {
        // The five public Result constants are all mapped above, so the fromResult
        // fallback is reachable in practice only via a null input.
        assertNull(EventType.fromResult(null));
    }
}
