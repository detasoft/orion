package pro.deta.orion.git.proxy;

import org.eclipse.jgit.api.Git;
import pro.deta.orion.git.client.GitClientFailure;
import pro.deta.orion.git.client.GitClientTransportException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider.SyncStatus.*;
import org.junit.jupiter.api.Test;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.pack.PackIngestionLimits;
import pro.deta.orion.git.nativestorage.pack.PackIngestor;
import pro.deta.orion.git.nativestorage.pack.PackIngestionResult;
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
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.schema.config.BootstrapSourceConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class NativeBootstrapGitPusherTest {
    private static final String NULL_ID = "0".repeat(40);

    @TempDir
    private Path tempDir;

    @Test
    void runtimeProxyPushesNativeCommitToFileUpstream() throws Exception {
        Upstream upstream = upstream("success", "first");
        BootstrapGitLocation location = location(upstream.bare());
        AtomicInteger rebuiltPacks = new AtomicInteger();
        NativeGitRepository repository = new NativeGitRepository(
                location.proxyName(), new LooseRefStore(), new LooseObjectStore(), location.refName()) {
            @Override
            public NativePackProducer fetch(NativeFetchRequest request) {
                rebuiltPacks.incrementAndGet();
                return super.fetch(request);
            }
        };
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
                ingest(repository, update),
                update.refUpdates(),
                true);

        assertThat(results).doesNotContain(RefUpdateResult.STALE);
        assertThat(proxy.syncObservation().status()).isEqualTo(SUCCESS);
        assertThat(proxy.syncObservation().observedAt()).isNotNull();
        assertThat(rebuiltPacks).hasValue(0);
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
        PackIngestionResult.Complete received = ingest(repository, update);
        repository.publishObjects(received.quarantine());
        Files.writeString(upstream.worktree().resolve("orion.xml"), "upstream change");
        upstream.git().add().addFilepattern("orion.xml").call();
        upstream.git().commit().setMessage("upstream update")
                .setAuthor("Test", "test@example.invalid").call();
        upstream.git().push().setRemote(upstream.bare().toUri().toString()).call();

        List<Boolean> accepted = new NativeBootstrapGitPusher().push(
                location,
                new GitFileClientTransport(),
                repository,
                received,
                update.refUpdates(),
                true);

        assertThat(accepted).containsExactly(false);
        var proxy = new BootstrapGitRuntimeProxy(location, repository,
                new BootstrapGitTransportFactory(new BootstrapSecretResolver(Map.of())),
                (selected, transport, target) -> { }, new NativeBootstrapGitPusher());
        assertThat(proxy.publish(received, update.refUpdates(), true)).containsExactly(RefUpdateResult.STALE);
        assertThat(proxy.syncObservation().status()).isEqualTo(CONFLICT);
        assertThat(repository.refs()).containsEntry(location.refName(), localOldId);
        assertThat(cloneContent(upstream.bare(), "conflict-checkout")).isEqualTo("upstream change");
        upstream.git().close();
    }

    @Test
    void runtimeRecordsNativePushAuthenticationFailureWithoutKeepingTheRemoteMessage() throws Exception {
        BootstrapGitLocation location = location(tempDir.resolve("upstream.git"));
        NativeGitRepository repository = repository(location);
        NativeGitFileUpdate update = repository.prepareFileUpdate(location.refName(),
                Map.of("orion.xml", new byte[]{1}), "update", GitCommitAuthor.EMPTY);
        var proxy = new BootstrapGitRuntimeProxy(location, repository,
                new BootstrapGitTransportFactory(new BootstrapSecretResolver(Map.of())),
                (selected, transport, target) -> { },
                (selected, transport, target, received, updates, atomic) -> new NativeBootstrapGitPusher().push(
                        selected, (service, uri, options) -> {
                            throw new GitClientTransportException(GitClientFailure.Kind.AUTHENTICATION_FAILED,
                                    false, "upstream-secret-response");
                        }, target, received, updates, atomic));

        assertThatThrownBy(() -> proxy.publish(ingest(repository, update), update.refUpdates(), true))
                .hasMessageNotContaining("upstream-secret-response").hasNoCause();
        assertThat(proxy.syncObservation().status()).isEqualTo(AUTHENTICATION_FAILED);
        assertThat(proxy.syncObservation().observedAt()).isNotNull();
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

    private static PackIngestionResult.Complete ingest(NativeGitRepository repository, NativeGitFileUpdate update) {
        byte[] pack = update.pack();
        ByteBuf input = Unpooled.wrappedBuffer(pack);
        try (PackIngestor ingestor = new PackIngestor(
                new PackIngestionLimits(pack.length, 100, 1024 * 1024), repository::readObject)) {
            return (PackIngestionResult.Complete) ingestor.accept(input);
        } finally {
            input.release();
        }
    }
}
