package io.jenkins.plugins.slackbuildevents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertNotNull;

import hudson.model.FreeStyleProject;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import jenkins.branch.BranchSource;
import jenkins.plugins.git.GitSCMSource;
import jenkins.plugins.git.traits.BranchDiscoveryTrait;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * End-to-end start-branch capture across the job types the fix targets: freestyle (configured
 * GitSCM spec), from-SCM pipeline (reflection into {@code CpsScmFlowDefinition}), plus the
 * completion-path non-regression (R2 — a START-only source never fires at completion).
 */
public class StartBranchIntegrationTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TestWebhookSender sender;
    private File repo;

    @Before
    public void setUp() throws Exception {
        // Allow checking out the throwaway local git repos this test builds (git plugin blocks
        // local-directory remotes by default as a controller-security measure).
        GitSCM.ALLOW_LOCAL_CHECKOUT = true;
        sender = new TestWebhookSender();
        SlackTestHelpers.installSeams(sender);
        SlackTestHelpers.addWebhookCredential("wh", "http://example.test/hook");
        SlackTestHelpers.config().setDefaultWebhookCredentialId("wh");
        repo = createGitRepo();
    }

    @Test
    public void freestyleStartResolvesConfiguredBranchNotNa() throws Exception {
        NotificationRule rule = SlackTestHelpers.rule("fs", List.of("start", "success"));
        rule.setStartTemplate("START ${SLACK_GIT_BRANCH}");
        rule.setSuccessTemplate("DONE ${SLACK_GIT_BRANCH}");
        SlackTestHelpers.config().setRules(List.of(rule));

        FreeStyleProject p = j.createFreeStyleProject("fs");
        p.setScm(gitScm(repo.getAbsolutePath(), "*/dev"));
        j.buildAndAssertSuccess(p);
        SlackTestHelpers.awaitDispatch();

        String start = bodyContaining("START");
        String done = bodyContaining("DONE");
        // The fix: start resolves the configured branch instead of the old N/A.
        assertThat(start, containsString("START dev"));
        assertThat(start, not(containsString("N/A")));
        // Completion resolves the real checked-out ref (unchanged chain), still a real branch.
        assertThat(done, containsString("dev"));
        assertThat(done, not(containsString("N/A")));
    }

    @Test
    public void fromScmPipelineStartResolvesConfiguredSpecViaReflection() throws Exception {
        NotificationRule rule = SlackTestHelpers.rule("ps", List.of("start"));
        rule.setStartTemplate("START ${SLACK_GIT_BRANCH}");
        SlackTestHelpers.config().setRules(List.of(rule));

        WorkflowJob job = j.createProject(WorkflowJob.class, "ps");
        job.setDefinition(new CpsScmFlowDefinition(gitScm(repo.getAbsolutePath(), "*/main"), "Jenkinsfile"));
        j.buildAndAssertSuccess(job);
        SlackTestHelpers.awaitDispatch();

        assertThat(bodyContaining("START"), containsString("START main"));
    }

    @Test
    public void completionDoesNotShowConfiguredSpec() throws Exception {
        // R2: a job whose configured spec is */main but whose checkout cannot resolve (bogus repo).
        NotificationRule rule = SlackTestHelpers.rule("r2", List.of("start", "failure"));
        rule.setStartTemplate("START ${SLACK_GIT_BRANCH}");
        rule.setFailureTemplate("DONE ${SLACK_GIT_BRANCH}");
        SlackTestHelpers.config().setRules(List.of(rule));

        FreeStyleProject p = j.createFreeStyleProject("r2");
        File missing = new File(tmp.getRoot(), "nonexistent-repo.git");
        p.setScm(gitScm(missing.getAbsolutePath(), "*/main"));
        p.scheduleBuild2(0).get(); // checkout fails → FAILURE; onStarted already fired
        SlackTestHelpers.awaitDispatch();

        // Start captured the configured spec; completion must NOT — it stays N/A (not "main").
        assertThat(bodyContaining("START"), containsString("START main"));
        String done = bodyContaining("DONE");
        assertThat(done, containsString("DONE N/A"));
        assertThat(done, not(containsString("DONE main")));
    }

    @Test
    public void multibranchStartResolvesBranchName() throws Exception {
        // Only the "main" branch job matches, so indexing's auto-build yields one START notification.
        NotificationRule rule = SlackTestHelpers.rule("mb/main", List.of("start"));
        rule.setStartTemplate("START ${SLACK_GIT_BRANCH}");
        SlackTestHelpers.config().setRules(List.of(rule));

        WorkflowMultiBranchProject mp = j.createProject(WorkflowMultiBranchProject.class, "mb");
        GitSCMSource source = new GitSCMSource(repo.getAbsolutePath());
        source.setTraits(List.of(new BranchDiscoveryTrait()));
        mp.getSourcesList().add(new BranchSource(source));

        mp.scheduleBuild2(0).getFuture().get(); // index → discover + auto-build branches
        j.waitUntilNoActivity();
        SlackTestHelpers.awaitDispatch();

        WorkflowJob main = mp.getItem("main");
        assertNotNull(main);
        // BRANCH_NAME is the multibranch start-time source (race-free); resolves to the branch name.
        assertThat(bodyContaining("START"), containsString("START main"));
    }

    private String bodyContaining(String needle) {
        for (String body : sender.bodies) {
            String text = JSONObject.fromObject(body)
                    .getJSONArray("attachments")
                    .getJSONObject(0)
                    .getString("text");
            if (text.contains(needle)) {
                return text;
            }
        }
        throw new AssertionError("no notification body containing: " + needle);
    }

    private static GitSCM gitScm(String remote, String spec) {
        // Plain path remote (not a file: URI), matching how controllers configure a local GitSCM.
        return new GitSCM(
                GitSCM.createRepoList(remote, null),
                List.of(new BranchSpec(spec)),
                null,
                null,
                Collections.emptyList());
    }

    private File createGitRepo() throws Exception {
        File dir = tmp.newFolder("repo");
        git(dir, "init", "-q");
        Files.writeString(
                new File(dir, "Jenkinsfile").toPath(),
                "node {\n  checkout scm\n  echo \"branch=${env.GIT_BRANCH}\"\n}\n",
                StandardCharsets.UTF_8);
        git(dir, "add", "-A");
        git(dir, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "init");
        git(dir, "branch", "-M", "main");
        git(dir, "branch", "dev");
        return dir;
    }

    private static void git(File dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed:\n" + out);
        }
    }
}
