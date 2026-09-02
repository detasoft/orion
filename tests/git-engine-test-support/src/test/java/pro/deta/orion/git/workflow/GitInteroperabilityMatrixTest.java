package pro.deta.orion.git.workflow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitInteroperabilityMatrixTest extends GitInteroperabilityMatrixRunner {
    private static final AtomicInteger INVOCATIONS = new AtomicInteger();

    @Test
    void normalizesNonFastForwardAndPreservesRemoteSnapshot() throws Exception {
        TestServer server = new TestServer("server");
        GitRemoteRepository remote = server.createRemoteRepository(
                java.nio.file.Files.createTempDirectory("git-matrix-state-"), "remote.git");
        GitScenarioContext context = new GitScenarioContext(new TestClient("client"), server, remote,
                java.nio.file.Files.createTempDirectory("git-matrix-context-"));

        GitOperationResult result = context.performAgainstRemote(
                () -> GitOperationResult.nonFastForward("update is not a fast-forward"));

        assertThat(result.status()).isEqualTo(GitOperationResult.Status.NON_FAST_FORWARD);
        assertThat(result.hasSnapshots()).isTrue();
        assertThat(result.stateUnchanged()).isTrue();
    }

    @Test
    void propagatesUnclassifiedFailuresInsteadOfTreatingThemAsSemanticRejections() {
        GitScenarioContext context = new GitScenarioContext(
                new TestClient("client"),
                new TestServer("server"),
                new GitRemoteRepository(Path.of("unused"), "git://example.test/unused.git"),
                Path.of("unused"));

        assertThatThrownBy(() -> context.perform(() -> {
            throw new IOException("connection failed before the operation reached the server");
        })).isInstanceOf(IOException.class)
                .hasMessage("connection failed before the operation reached the server");
    }

    @Test
    void rejectsWorkTreeNamesThatAliasOrEscapeTheInvocationDirectory() throws Exception {
        Path invocationDirectory = java.nio.file.Files.createTempDirectory("git-matrix-context-");
        GitScenarioContext context = new GitScenarioContext(
                new TestClient("client"),
                new TestServer("server"),
                new GitRemoteRepository(Path.of("unused"), "git://example.test/unused.git"),
                invocationDirectory);

        assertThatIllegalArgumentException().isThrownBy(() -> context.workTreeDirectory("."));
        assertThatIllegalArgumentException().isThrownBy(() -> context.workTreeDirectory(".."));
        try (var children = java.nio.file.Files.list(invocationDirectory)) {
            assertThat(children).isEmpty();
        }
    }

    @Test
    void capturesRefsHistoryTreesModesBlobIdsAndContentHashes() throws Exception {
        Path directory = java.nio.file.Files.createTempDirectory("git-matrix-snapshot-");
        GitRemoteRepository remote = GitRemoteRepository.createBare(directory.resolve("remote.git"));
        try (GitWorkTree workTree = GitClients.jgit().init(directory.resolve("client"))) {
            workTree.writeFile("README.md", "first version\n");
            workTree.add("README.md");
            workTree.commit("first");
            workTree.writeFile("README.md", "second version\n");
            workTree.add("README.md");
            workTree.commit("second");
            workTree.addRemote("origin", remote);
            workTree.push("origin", "main");

            RepositorySnapshot snapshot = RepositorySnapshot.capture(remote.directory());

            assertThat(snapshot.headSymref()).isEqualTo("refs/heads/main");
            assertThat(snapshot.refs()).containsKey("refs/heads/main");
            assertThat(snapshot.commits()).hasSize(2);
            RepositorySnapshot.Commit commit = snapshot.commits().get(snapshot.refs().get("refs/heads/main"));
            assertThat(commit.parents()).hasSize(1);
            assertThat(commit.entries()).containsKey("README.md");
            assertThat(commit.entries().get("README.md").mode()).isEqualTo(0100644);
            assertThat(commit.entries().get("README.md").objectId()).hasSize(40);
            assertThat(commit.entries().get("README.md").contentHash()).hasSize(64);
        }
    }

    @Test
    void reportsFirstCanonicalTreeEntryDifferenceWithBothStates() {
        RepositorySnapshot.TreeEntry expectedEntry = new RepositorySnapshot.TreeEntry(
                0100644, "1111111111111111111111111111111111111111", "expected-content-hash");
        RepositorySnapshot.TreeEntry actualEntry = new RepositorySnapshot.TreeEntry(
                0100755, "2222222222222222222222222222222222222222", "actual-content-hash");
        RepositorySnapshot expected = snapshotWithEntry(expectedEntry);
        RepositorySnapshot actual = snapshotWithEntry(actualEntry);

        assertThat(expected.difference(actual))
                .isEqualTo("tree commit-id script.sh expected=" + expectedEntry + " actual=" + actualEntry);
    }

    @Test
    void invocationNameIdentifiesScenarioClientAndServerEngines() {
        GitScenario scenario = scenario("clone");
        GitMatrixInvocation invocation = new GitMatrixInvocation(
                scenario, new TestClient("jgit"), new TestServer("orion"));

        assertThat(invocation).hasToString("clone [jgit -> orion]");
    }

    @Override
    protected Stream<GitMatrixInvocation> matrixInvocations() {
        return Stream.of(
                new GitMatrixInvocation(scenario("minimal"),
                        new TestClient("client-one"), new TestServer("server-one")),
                new GitMatrixInvocation(scenario("minimal"),
                        new TestClient("client-two"), new TestServer("server-two")));
    }

    private static RepositorySnapshot snapshotWithEntry(RepositorySnapshot.TreeEntry entry) {
        RepositorySnapshot.Commit commit = new RepositorySnapshot.Commit(
                "tree-id", List.of(), Map.of("script.sh", entry));
        return RepositorySnapshot.of(
                "refs/heads/main", Map.of("refs/heads/main", "commit-id"), Map.of("commit-id", commit));
    }

    private static GitScenario scenario(String name) {
        return new GitScenario() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Set<GitCapability> requiredCapabilities() {
                return Set.of(GitCapability.INITIALIZE);
            }

            @Override
            public void run(GitScenarioContext context) throws Exception {
                int invocation = INVOCATIONS.incrementAndGet();
                assertThat(context.workTreeDirectory("client").getFileName()).hasToString("client");
                assertThat(context.remote().directory().getFileName()).hasToString("remote.git");
                assertThat(context.client().engine().name()).startsWith("client-");
                assertThat(context.server().engine().name()).startsWith("server-");
                assertThat(invocation).isLessThanOrEqualTo(2);
            }
        };
    }

    private record TestClient(String name) implements GitClient {
        @Override
        public boolean available() {
            return true;
        }

        @Override
        public GitWorkTree init(Path directory) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GitWorkTree clone(String remoteUri, Path directory) {
            throw new UnsupportedOperationException();
        }
    }

    private record TestServer(String name) implements GitServer {
        @Override
        public Set<GitCapability> capabilities() {
            return GitCapability.all();
        }

        @Override
        public GitRemoteRepository createRemoteRepository(
                Path directory,
                String repositoryName) throws IOException {
            return GitRemoteRepository.createBare(directory.resolve(repositoryName));
        }
    }
}
