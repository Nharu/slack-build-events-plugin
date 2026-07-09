package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertEquals;

import hudson.model.FreeStyleBuild;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.lang.reflect.Field;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Branch coverage for {@link SlackDurationTokenMacro#compute} — recorded duration, the
 * live-compute fallback, and the negative clamp. Uses a real {@link FreeStyleBuild} plus
 * reflection (Run has no public setter for {@code duration}/{@code startTime} in 2.479.3);
 * this suite has no Mockito on its classpath.
 */
public class SlackDurationTokenMacroTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void usesRecordedDurationWhenPresent() throws Exception {
        FreeStyleBuild build = j.buildAndAssertSuccess(j.createFreeStyleProject("dur-recorded"));
        setLongField(build, "duration", 7_503_000L); // 2h 5m 3s → "2h 5m"

        assertEquals("2h 5m", compute(build));
    }

    @Test
    public void computesLiveWhenDurationNotYetRecorded() throws Exception {
        FreeStyleBuild build = j.buildAndAssertSuccess(j.createFreeStyleProject("dur-live"));
        // Not yet recorded → the macro falls back to now - startTime. Anchoring startTime
        // ~2h5m30s in the past keeps the elapsed value inside the [2h5m, 2h6m) bucket despite
        // millisecond test jitter, so the top-two-unit rendering stays "2h 5m".
        setLongField(build, "duration", 0L);
        setLongField(build, "startTime", System.currentTimeMillis() - 7_530_000L);

        assertEquals("2h 5m", compute(build));
    }

    @Test
    public void clampsNegativeElapsedToZero() throws Exception {
        FreeStyleBuild build = j.buildAndAssertSuccess(j.createFreeStyleProject("dur-negative"));
        // Duration unrecorded and a future start time → now - startTime is negative → clamp to 0s.
        setLongField(build, "duration", 0L);
        setLongField(build, "startTime", System.currentTimeMillis() + 3_600_000L);

        assertEquals("0s", compute(build));
    }

    private static String compute(Run<?, ?> build) {
        return new SlackDurationTokenMacro().compute(build, TaskListener.NULL);
    }

    private static void setLongField(Run<?, ?> build, String name, long value) throws Exception {
        Field field = Run.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(build, value);
    }
}
