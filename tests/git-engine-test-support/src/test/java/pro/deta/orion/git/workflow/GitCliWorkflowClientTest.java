package pro.deta.orion.git.workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitCliWorkflowClientTest {
    @Test
    void createsTheSameDeterministicCommitAsJGit(@TempDir Path directory) throws Exception {
        GitClient cli = new GitCliWorkflowClient("git", "git");

        try (GitWorkTree gitWorkTree = cli.init(directory.resolve("git"));
                GitWorkTree jgitWorkTree = GitClients.jgit().init(directory.resolve("jgit"))) {
            commitFixture(gitWorkTree);
            commitFixture(jgitWorkTree);

            assertThat(gitWorkTree.head()).isEqualTo(jgitWorkTree.head());
            assertThat(gitWorkTree.snapshot().difference(jgitWorkTree.snapshot())).isNull();
            assertThat(cli.diagnostics()).startsWith("git version ");
        }
    }

    @Test
    void reportsMissingCanonicalGitAsAPrerequisiteFailure() {
        GitClient client = new GitCliWorkflowClient("missing-git", "definitely-missing-orion-git");

        assertThatIllegalStateException()
                .isThrownBy(client::requireAvailable)
                .withMessageContaining("Canonical Git prerequisite is unavailable")
                .withMessageContaining("definitely-missing-orion-git");
    }

    @Test
    void preservesCrLfBytesConsistentlyAcrossEngines(@TempDir Path directory) throws Exception {
        try (GitWorkTree gitWorkTree = GitClients.git().init(directory.resolve("git-crlf"));
                GitWorkTree jgitWorkTree = GitClients.jgit().init(directory.resolve("jgit-crlf"))) {
            gitWorkTree.writeFile("lines.txt", "first\r\nsecond\r\n");
            jgitWorkTree.writeFile("lines.txt", "first\r\nsecond\r\n");
            gitWorkTree.add("lines.txt");
            jgitWorkTree.add("lines.txt");
            gitWorkTree.commit("crlf");
            jgitWorkTree.commit("crlf");

            assertThat(gitWorkTree.head()).isEqualTo(jgitWorkTree.head());
            assertThat(gitWorkTree.snapshot().difference(jgitWorkTree.snapshot())).isNull();
        }
    }

    @Test
    void runsBranchAndRejectedPushCatalogScenariosThroughCanonicalGit() throws Exception {
        for (GitScenario scenario : GitWorkflowScenarios.catalog()) {
            if (Set.of("second-branch-fetch-and-checkout", "reject-stale-non-fast-forward")
                    .contains(scenario.name())) {
                GitInteroperabilityHarness.run(scenario, GitClients.git(), GitServers.jgit());
            }
        }
    }

    @Test
    void propagatesAnUnclassifiedPushFailure(@TempDir Path directory) throws Exception {
        try (GitWorkTree workTree = GitClients.git().init(directory.resolve("source"))) {
            commitFixture(workTree);
            workTree.addRemote("origin", new GitRemoteRepository(
                    directory.resolve("missing"), "git://127.0.0.1:1/missing.git"));

            assertThatThrownBy(() -> workTree.pushResult("origin", "main"))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining("Canonical Git command failed");
        }
    }

    @Test
    void distinguishesLiteralAllCapabilitiesFromTheSymmetricBaseline() {
        assertThat(GitCapability.all()).containsExactlyInAnyOrder(GitCapability.values());
        assertThat(GitCapability.symmetric())
                .doesNotContain(GitCapability.CREATE_MISSING_REPOSITORY_ON_PUSH);
    }

    private static void commitFixture(GitWorkTree workTree) throws Exception {
        workTree.writeFile("README.md", "reference engine\n");
        workTree.add("README.md");
        workTree.commit("initial");
    }

}
