package pro.deta.orion.git.projection.parity;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.BaseOrionTest;
import pro.deta.orion.git.workflow.GitClient;
import pro.deta.orion.git.workflow.GitClients;
import pro.deta.orion.git.workflow.GitRemoteRepository;
import pro.deta.orion.git.workflow.GitWorkTree;
import pro.deta.orion.git.workflow.RepositorySnapshot;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GitCliWorkflowParityTest extends BaseOrionTest {
    @Test
    void canonicalGitPushesAndJGitClonesSameRepositoryState() throws Exception {
        comparePushThenClone(gitCli(), GitClients.jgit());
    }

    @Test
    void jGitPushesAndCanonicalGitClonesSameRepositoryState() throws Exception {
        comparePushThenClone(GitClients.jgit(), gitCli());
    }

    @Test
    void canonicalGitPushesUpdateAndJGitPullsSameRepositoryState() throws Exception {
        comparePushThenPull(gitCli(), GitClients.jgit());
    }

    @Test
    void jGitPushesUpdateAndCanonicalGitPullsSameRepositoryState() throws Exception {
        comparePushThenPull(GitClients.jgit(), gitCli());
    }

    @Disabled("TODO: compare explicit fetch + checkout workflow after Git CLI fetch scenarios are stable.")
    @Test
    void canonicalGitPushesAndJGitFetchesSameRepositoryState() {
    }

    @Disabled("TODO: compare two-client round trip: Git CLI pushes, JGit pulls and pushes, Git CLI pulls back.")
    @Test
    void canonicalGitAndJGitRoundTripPushPullMatches() {
    }

    private void comparePushThenClone(GitClient producerClient, GitClient consumerClient) throws Exception {
        Path workspace = createTestRepositoryDirectory();
        GitRemoteRepository remote = GitRemoteRepository.createBare(workspace.resolve("remote.git"));

        try (GitWorkTree producer = producerClient.init(workspace.resolve("producer"));
             GitWorkTree consumer = cloneAfterInitialPush(producer, consumerClient, remote, workspace)) {
            assertSameSnapshot(producer, consumer, producerClient, consumerClient, "clone");
        }
    }

    private void comparePushThenPull(GitClient producerClient, GitClient consumerClient) throws Exception {
        Path workspace = createTestRepositoryDirectory();
        GitRemoteRepository remote = GitRemoteRepository.createBare(workspace.resolve("remote.git"));

        try (GitWorkTree producer = producerClient.init(workspace.resolve("producer"));
             GitWorkTree consumer = cloneAfterInitialPush(producer, consumerClient, remote, workspace)) {
            writeSecondCommit(producer);
            producer.push("origin", "main");
            consumer.pull("origin", "main");

            assertSameSnapshot(producer, consumer, producerClient, consumerClient, "pull");
        }
    }

    private GitWorkTree cloneAfterInitialPush(
            GitWorkTree producer,
            GitClient consumerClient,
            GitRemoteRepository remote,
            Path workspace) throws Exception {
        writeInitialCommit(producer);
        producer.addRemote("origin", remote);
        producer.push("origin", "main");
        return consumerClient.clone(remote, workspace.resolve("consumer"));
    }

    private static void writeInitialCommit(GitWorkTree repository) throws Exception {
        repository.writeFile("README.md", "hello from first commit\n");
        repository.writeFile("src/main.txt", "initial source\n");
        repository.add("README.md", "src/main.txt");
        repository.commit("initial");
    }

    private static void writeSecondCommit(GitWorkTree repository) throws Exception {
        repository.writeFile("README.md", "hello from second commit\n");
        repository.writeFile("docs/spec.txt", "projection parity workflow\n");
        repository.add("README.md", "docs/spec.txt");
        repository.commit("second");
    }

    private static void assertSameSnapshot(
            GitWorkTree expected,
            GitWorkTree actual,
            GitClient producerClient,
            GitClient consumerClient,
            String operation) throws Exception {
        RepositorySnapshot expectedSnapshot = expected.snapshot();
        RepositorySnapshot actualSnapshot = actual.snapshot();

        assertThat(actualSnapshot.bytes())
                .as("%s -> %s %s should expose the same repository state%nexpected:%n%s%nactual:%n%s",
                        producerClient.name(),
                        consumerClient.name(),
                        operation,
                        expectedSnapshot.text(),
                        actualSnapshot.text())
                .isEqualTo(expectedSnapshot.bytes());
    }

    private static GitClient gitCli() {
        GitClient client = GitClients.git();
        assumeTrue(client.available(), "canonical Git executable is not available");
        return client;
    }
}
