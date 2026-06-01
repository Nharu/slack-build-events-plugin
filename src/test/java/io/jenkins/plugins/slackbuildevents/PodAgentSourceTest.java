package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertEquals;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.Revision;
import hudson.plugins.git.util.Build;
import hudson.plugins.git.util.BuildData;
import java.util.List;
import org.eclipse.jgit.lib.ObjectId;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * (f2) Run-attached git BuildData source: commit/branch resolve from the build action
 * (no workspace needed), the path that keeps working on ephemeral/pod agents.
 */
public class PodAgentSourceTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void commitAndBranchComeFromBuildDataWithoutWorkspace() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("pod");
        FreeStyleBuild b = j.buildAndAssertSuccess(p);

        ObjectId sha = ObjectId.fromString("0123456789abcdef0123456789abcdef01234567");
        Revision revision = new Revision(sha, List.of(new Branch("origin/main", sha)));
        BuildData data = new BuildData();
        data.saveBuild(new Build(revision, revision, b.getNumber(), Result.SUCCESS));
        b.addAction(data);

        assertEquals("0123456", TokenMacro.expandAll(b, null, TaskListener.NULL, "${SLACK_GIT_COMMIT}"));
        assertEquals("origin/main", TokenMacro.expandAll(b, null, TaskListener.NULL, "${SLACK_GIT_BRANCH}"));
    }

    @Test
    public void branchFallsBackToNaWhenUnknown() throws Exception {
        FreeStyleBuild b = j.buildAndAssertSuccess(j.createFreeStyleProject("plain"));
        assertEquals("N/A", TokenMacro.expandAll(b, null, TaskListener.NULL, "${SLACK_GIT_BRANCH}"));
        assertEquals("", TokenMacro.expandAll(b, null, TaskListener.NULL, "${SLACK_GIT_COMMIT}"));
    }
}
