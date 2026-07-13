package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import hudson.EnvVars;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.PasswordParameterValue;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.scm.NullSCM;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * {@link GitMacroSupport#configuredBranch} GitSCM gating and {@link GitMacroSupport#parameterEnv}
 * secret exclusion — the parts of the accept/reject table that need a real GitSCM / build.
 */
public class ConfiguredBranchTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private static GitSCM gitScm(BranchSpec... branches) {
        return new GitSCM(
                GitSCM.createRepoList("https://example.test/r.git", null),
                List.of(branches),
                null,
                null,
                Collections.emptyList());
    }

    @Test
    public void singleSpecGitScmNormalizes() {
        assertEquals("main", GitMacroSupport.configuredBranch(gitScm(new BranchSpec("*/main")), new EnvVars()));
    }

    @Test
    public void multiSpecAndNonGitAreNull() {
        assertNull(
                "multi-spec is ambiguous",
                GitMacroSupport.configuredBranch(
                        gitScm(new BranchSpec("*/main"), new BranchSpec("*/dev")), new EnvVars()));
        assertNull(GitMacroSupport.configuredBranch(new NullSCM(), new EnvVars()));
        assertNull(GitMacroSupport.configuredBranch(null, new EnvVars()));
    }

    @Test
    public void parameterEnvExpandsStringButExcludesSecrets() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("params");
        p.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("BRANCH", "def"),
                new PasswordParameterDefinition("PW", "", "password param")));
        FreeStyleBuild b = j.assertBuildStatusSuccess(p.scheduleBuild2(
                0,
                new ParametersAction(
                        new StringParameterValue("BRANCH", "dev"),
                        new PasswordParameterValue("PW", "s3cr3t"))));

        EnvVars pe = GitMacroSupport.parameterEnv(b);
        assertEquals("dev", pe.get("BRANCH"));
        assertNull("secret param must not leak into the expansion env", pe.get("PW"));

        // And a spec that references a secret param resolves to null (unresolved ${PW}), not the plaintext.
        assertEquals("dev", GitMacroSupport.configuredBranch(gitScm(new BranchSpec("*/${BRANCH}")), pe));
        assertNull(GitMacroSupport.configuredBranch(gitScm(new BranchSpec("*/${PW}")), pe));
    }
}
