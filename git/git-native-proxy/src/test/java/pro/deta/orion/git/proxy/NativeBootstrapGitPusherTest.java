package pro.deta.orion.git.proxy;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.client.GitFileClientTransport;
import pro.deta.orion.git.client.GitReceivePackResult;
import pro.deta.orion.git.client.GitRemoteAdvertisement;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitFileUpdate;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;
import pro.deta.orion.schema.config.BootstrapSourceConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NativeBootstrapGitPusherTest {
    private static final String NULL_ID = "0".repeat(40);

    @TempDir
    private Path tempDir;

    @Test
    void runtimeProxyPushesNativeCommitToFileUpstream() throws Exception {
        Upstream upstream = upstream("success", "first");
        BootstrapGitLocation location = location(upstream.bare());
        NativeGitRepository repository = repository(location);
        NativeBootstrapGitFetcher fetcher = new NativeBootstrapGitFetcher();
        fetcher.fetch(location, new GitFileClientTransport(), repository);
        NativeGitFileUpdate update = repository.prepareFileUpdate(
                location.refName(),
                Map.of("orion.xml", "from proxy".getBytes()),
                "proxy update",
                GitCommitAuthor.EMPTY);
        BootstrapGitRuntimeProxy proxy = new BootstrapGitRuntimeProxy(
                location,
                repository,
                new BootstrapGitTransportFactory(new BootstrapSecretResolver(Map.of())),
                fetcher,
                new NativeBootstrapGitPusher());

        List<RefUpdateResult> results = proxy.publish(
                update.objects(),
                update.refUpdates(),
                true);

        assertThat(results).doesNotContain(RefUpdateResult.STALE);
        try (Git bareGit = Git.open(upstream.bare().toFile())) {
            assertThat(repository.refs().get(location.refName()))
                    .isEqualTo(bareGit.getRepository().resolve(location.refName()).name());
        }
        assertThat(cloneContent(upstream.bare(), "success-checkout")).isEqualTo("from proxy");
        upstream.git().close();
    }

    @Test
    void upstreamRejectsStaleExpectedObjectWithoutAdvancingProxyRef() throws Exception {
        Upstream upstream = upstream("conflict", "first");
        BootstrapGitLocation location = location(upstream.bare());
        NativeGitRepository repository = repository(location);
        new NativeBootstrapGitFetcher().fetch(location, new GitFileClientTransport(), repository);
        String localOldId = repository.refs().get(location.refName());
        NativeGitFileUpdate update = repository.prepareFileUpdate(
                location.refName(),
                Map.of("orion.xml", "proxy change".getBytes()),
                "proxy update",
                GitCommitAuthor.EMPTY);
        repository.publishObjects(update.objects());
        Files.writeString(upstream.worktree().resolve("orion.xml"), "upstream change");
        upstream.git().add().addFilepattern("orion.xml").call();
        upstream.git().commit().setMessage("upstream update")
                .setAuthor("Test", "test@example.invalid").call();
        upstream.git().push().setRemote(upstream.bare().toUri().toString()).call();

        List<Boolean> accepted = new NativeBootstrapGitPusher().push(
                location,
                new GitFileClientTransport(),
                repository,
                update.refUpdates(),
                true);

        assertThat(accepted).containsExactly(false);
        assertThat(repository.refs()).containsEntry(location.refName(), localOldId);
        assertThat(cloneContent(upstream.bare(), "conflict-checkout")).isEqualTo("upstream change");
        upstream.git().close();
    }

    @Test
    void mapsReceiveStatusesToRequestedRefOrder() {
        List<LooseRefStore.Update> updates = List.of(
                new LooseRefStore.Update("refs/heads/first", NULL_ID, "1".repeat(40)),
                new LooseRefStore.Update("refs/heads/second", NULL_ID, "2".repeat(40)));
        GitReceivePackResult result = new GitReceivePackResult(
                new GitRemoteAdvertisement(Set.of(), List.of()),
                "ok",
                List.of(
                        new GitReceivePackResult.RefStatus("refs/heads/second", false, "stale"),
                        new GitReceivePackResult.RefStatus("refs/heads/first", true, "")));

        assertThat(NativeBootstrapGitPusher.accepted(updates, result))
                .containsExactly(true, false);
    }

    @Test
    void requiresAtomicCapabilityOnlyForMultipleRefUpdates() {
        assertThat(NativeBootstrapGitPusher.requestAtomic(1, true)).isFalse();
        assertThat(NativeBootstrapGitPusher.requestAtomic(2, true)).isTrue();
        assertThat(NativeBootstrapGitPusher.requestAtomic(2, false)).isFalse();
    }

    private NativeGitRepository repository(BootstrapGitLocation location) {
        return new InMemoryNativeGitRepositoryProvider()
                .create(location.proxyName())
                .valueOrFailure("create proxy");
    }

    private Upstream upstream(String name, String content) throws Exception {
        Path worktree = tempDir.resolve(name + "-worktree");
        Path bare = tempDir.resolve(name + "-upstream.git");
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

    private String cloneContent(Path bare, String directory) throws Exception {
        Path checkout = tempDir.resolve(directory);
        try (Git ignored = Git.cloneRepository()
                .setURI(bare.toUri().toString())
                .setDirectory(checkout.toFile())
                .call()) {
            return Files.readString(checkout.resolve("orion.xml"));
        }
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
