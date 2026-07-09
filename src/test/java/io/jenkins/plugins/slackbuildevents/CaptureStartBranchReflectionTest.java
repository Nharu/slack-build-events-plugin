package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import hudson.scm.NullSCM;
import hudson.scm.SCM;
import org.junit.Test;

/**
 * The reflection extractor {@link GitMacroSupport#scmFromGetScm} must swallow any {@code Throwable}
 * from {@code getScm()} — including a {@link NoClassDefFoundError} raised when the git/workflow
 * optional dependency is absent — and return {@code null} rather than propagate it to the build.
 */
public class CaptureStartBranchReflectionTest {

    /** Stub whose {@code getScm()} fails as it would when a git class fails to load. */
    public static final class ThrowingDefinition {
        public SCM getScm() {
            throw new NoClassDefFoundError("hudson/plugins/git/GitSCM");
        }
    }

    public static final class NullScmDefinition {
        public SCM getScm() {
            return new NullSCM();
        }
    }

    public static final class NonScmDefinition {
        public Object getScm() {
            return "not an scm";
        }
    }

    public static final class NoGetScmDefinition {
        // no getScm() method at all → NoSuchMethodException → null
    }

    @Test
    public void swallowsErrorFromGetScm() {
        assertNull(GitMacroSupport.scmFromGetScm(new ThrowingDefinition()));
    }

    @Test
    public void returnsScmWhenPresent() {
        assertNotNull(GitMacroSupport.scmFromGetScm(new NullScmDefinition()));
    }

    @Test
    public void rejectsNonScmReturnValue() {
        assertNull(GitMacroSupport.scmFromGetScm(new NonScmDefinition()));
    }

    @Test
    public void returnsNullWhenNoGetScm() {
        assertNull(GitMacroSupport.scmFromGetScm(new NoGetScmDefinition()));
    }
}
