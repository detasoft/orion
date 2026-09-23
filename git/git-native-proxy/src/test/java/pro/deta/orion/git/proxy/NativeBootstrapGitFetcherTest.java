package pro.deta.orion.git.proxy;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.client.GitFileClientTransport;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.GitFile;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.schema.config.BootstrapSourceConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeBootstrapGitFetcherTest {
    @TempDir
    private Path tempDir;

    @Test
    void fetchesAndRefreshesSelectedRefIntoNativeRepository() throws Exception {
        Upstream upstream = upstream("first");
        BootstrapGitLocation location = location(upstream.bare());
        NativeGitRepository repository = new InMemoryNativeGitRepositoryProvider()
                .create(location.proxyName()).valueOrFailure("create proxy");
        NativeBootstrapGitFetcher fetcher = new NativeBootstrapGitFetcher();

        try {
            fetcher.fetch(location, new GitFileClientTransport(), repository);
            assertThat(repository.loadFiles(location.refName(), List.of("orion.xml")).files())
                    .containsEntry("orion.xml", GitFile.regular("first".getBytes()));

            Files.writeString(upstream.worktree().resolve("orion.xml"), "second");
            upstream.git().add().addFilepattern("orion.xml").call();
            upstream.git().commit().setMessage("second").setAuthor("Test", "test@example.invalid").call();
            upstream.git().push().setRemote(upstream.bare().toUri().toString()).call();

            fetcher.fetch(location, new GitFileClientTransport(), repository);
            assertThat(repository.loadFiles(location.refName(), List.of("orion.xml")).files())
                    .containsEntry("orion.xml", GitFile.regular("second".getBytes()));
        } finally {
            upstream.git().close();
        }
    }

    @Test
    void missingUpstreamFailsWithoutDisclosingItsPath() {
        Path missing = tempDir.resolve("credential-looking-upstream.git");
        BootstrapGitLocation location = location(missing);
        NativeGitRepository repository = new InMemoryNativeGitRepositoryProvider()
                .create(location.proxyName()).valueOrFailure("create proxy");

        assertThatThrownBy(() -> {
            new NativeBootstrapGitFetcher().fetch(
                    location,
                    new GitFileClientTransport(),
                    repository);
        }).isInstanceOf(BootstrapGitProxyException.class)
                .hasMessage("Remote Git bootstrap failed during upstream discovery")
                .hasMessageNotContaining("credential-looking")
                .hasMessageNotContaining("sensitive-token");
    }

    @Test
    void rewindsSelectedRefToObjectAlreadyPresentWithoutPackData() throws Exception {
        Upstream upstream = upstream("first");
        BootstrapGitLocation location = location(upstream.bare());
        NativeGitRepository repository = new InMemoryNativeGitRepositoryProvider()
                .create(location.proxyName()).valueOrFailure("create proxy");
        NativeBootstrapGitFetcher fetcher = new NativeBootstrapGitFetcher();
        String firstId = upstream.git().getRepository().resolve("refs/heads/main").name();
        try {
            Files.writeString(upstream.worktree().resolve("orion.xml"), "second");
            upstream.git().add().addFilepattern("orion.xml").call();
            upstream.git().commit().setMessage("second")
                    .setAuthor("Test", "test@example.invalid").call();
            upstream.git().push().setRemote(upstream.bare().toUri().toString()).call();
            fetcher.fetch(location, new GitFileClientTransport(), repository);

            upstream.git().reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                    .setRef(firstId).call();
            upstream.git().push().setRemote(upstream.bare().toUri().toString())
                    .setForce(true).call();
            fetcher.fetch(location, new GitFileClientTransport(), repository);

            assertThat(repository.refs()).containsEntry(location.refName(), firstId);
            assertThat(repository.loadFiles(location.refName(), List.of("orion.xml")).files())
                    .containsEntry("orion.xml", GitFile.regular("first".getBytes()));
        } finally {
            upstream.git().close();
        }
    }

    @Test
    void rejectsIncompleteObjectClosureBeforeRefPublication() {
        NativeGitRepository repository = new InMemoryNativeGitRepositoryProvider()
                .create("proxy").valueOrFailure("create proxy");
        ObjectId commit = repository.writeObject(
                GitObjectType.COMMIT,
                ("tree " + "f".repeat(40) + "\n\nmissing tree\n").getBytes());

        assertThatThrownBy(() -> NativeFetchedRefPublisher.publish(
                repository,
                RefUpdate.fromWire("refs/heads/main", "0".repeat(40), commit.toHex())))
                .isInstanceOf(BootstrapGitProxyException.class)
                .hasMessage("Remote Git bootstrap failed during complete object validation");
        assertThat(repository.refs()).isEmpty();
    }

    @Test
    void reportsConcurrentLocalRefPublicationAsAConflictWithoutChangingRefs() throws Exception {
        NativeGitRepository repository = new InMemoryNativeGitRepositoryProvider()
                .create("proxy").valueOrFailure("create proxy");
        repository.saveFiles("refs/heads/main", Map.of("orion.xml", GitFile.regular(new byte[]{1})), Set.of(),
                "local", GitCommitAuthor.EMPTY);
        repository.saveFiles("refs/heads/incoming", Map.of("orion.xml", GitFile.regular(new byte[]{2})), Set.of(),
                "remote", GitCommitAuthor.EMPTY);
        Map<String, String> before = repository.refs();

        assertThatThrownBy(() -> NativeFetchedRefPublisher.publish(repository,
                RefUpdate.fromWire("refs/heads/main", "1".repeat(40), before.get("refs/heads/incoming"))))
                .isInstanceOfSatisfying(BootstrapGitProxyException.class, failure ->
                        assertThat(failure.status()).isEqualTo(ProxyAwareNativeGitRepositoryProvider.SyncStatus.CONFLICT));
        assertThat(repository.refs()).isEqualTo(before);
    }

    private Upstream upstream(String content) throws Exception {
        Path worktree = tempDir.resolve("worktree");
        Path bare = tempDir.resolve("upstream.git");
        Git git = Git.init().setDirectory(worktree.toFile()).setInitialBranch("main").call();
        Files.writeString(worktree.resolve("orion.xml"), content);
        git.add().addFilepattern("orion.xml").call();
        git.commit().setMessage("first").setAuthor("Test", "test@example.invalid").call();
        try (Git ignored = Git.cloneRepository()
                .setURI(worktree.toUri().toString())
                .setDirectory(bare.toFile())
                .setBare(true)
                .call()) {
            // Bare fixture is ready.
        }
        return new Upstream(git, worktree, bare);
    }

    private static BootstrapGitLocation location(Path upstream) {
        BootstrapSourceConfig config = new BootstrapSourceConfig();
        config.setLocation("git+" + upstream.toUri() + "?ref=main");
        config.setPath("orion.xml");
        config.setAuth(Map.of());
        return BootstrapGitLocation.parse(config);
    }

    private record Upstream(Git git, Path worktree, Path bare) {
    }
}
