package pro.deta.orion.git.workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

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

    private static void commitFixture(GitWorkTree workTree) throws Exception {
        workTree.writeFile("README.md", "reference engine\n");
        workTree.add("README.md");
        workTree.commit("initial");
    }

}
