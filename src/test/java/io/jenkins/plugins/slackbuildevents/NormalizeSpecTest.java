package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import hudson.EnvVars;
import org.junit.Test;

/**
 * Pure-function coverage of the configured-spec normalizer's accept/reject table. No Jenkins
 * needed: {@link GitMacroSupport#normalizeSpec} takes a raw spec and a plain {@link EnvVars}.
 */
public class NormalizeSpecTest {

    private static EnvVars env(String... kv) {
        EnvVars env = new EnvVars();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            env.put(kv[i], kv[i + 1]);
        }
        return env;
    }

    @Test
    public void acceptsAndStripsRealSpecs() {
        EnvVars empty = env();
        assertEquals("main", GitMacroSupport.normalizeSpec("*/main", empty));
        assertEquals("main", GitMacroSupport.normalizeSpec("origin/main", empty));
        assertEquals("feature/x", GitMacroSupport.normalizeSpec("origin/feature/x", empty));
        assertEquals("develop", GitMacroSupport.normalizeSpec("refs/heads/develop", empty));
        assertEquals("release/1", GitMacroSupport.normalizeSpec("refs/remotes/origin/release/1", empty));
        assertEquals("v1", GitMacroSupport.normalizeSpec("refs/tags/v1", empty));
    }

    @Test
    public void expandsParameterReferences() {
        EnvVars env = env("BRANCH", "dev");
        assertEquals("dev", GitMacroSupport.normalizeSpec("${BRANCH}", env));
        assertEquals("dev", GitMacroSupport.normalizeSpec("*/${BRANCH}", env));
    }

    @Test
    public void rejectsUnresolvableOrNonConcreteSpecs() {
        EnvVars empty = env();
        assertNull("unset param stays literal ${...}", GitMacroSupport.normalizeSpec("${UNSET}", empty));
        assertNull(GitMacroSupport.normalizeSpec("*", empty));
        assertNull(GitMacroSupport.normalizeSpec("**", empty));
        assertNull(GitMacroSupport.normalizeSpec("feature/*", empty));
        assertNull("BranchSpec regex", GitMacroSupport.normalizeSpec(":^(?!origin/master).*", empty));
        assertNull(GitMacroSupport.normalizeSpec("HEAD", empty));
        assertNull(GitMacroSupport.normalizeSpec("", empty));
        assertNull(GitMacroSupport.normalizeSpec("   ", empty));
        assertNull(GitMacroSupport.normalizeSpec(null, empty));
    }

    @Test
    public void stripsOnlyLeadingPrefixes() {
        EnvVars empty = env();
        // Only the first, leading prefix is stripped; a mid-name origin/ is preserved.
        assertEquals("feature/origin/x", GitMacroSupport.normalizeSpec("*/feature/origin/x", empty));
    }
}
