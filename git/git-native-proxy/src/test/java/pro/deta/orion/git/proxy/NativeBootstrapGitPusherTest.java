package pro.deta.orion.git.proxy;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.client.GitClientFailure;
import pro.deta.orion.git.client.GitClientTransportException;
import pro.deta.orion.git.client.GitFileClientTransport;
import pro.deta.orion.git.client.GitReceivePackResult;
import pro.deta.orion.git.client.GitRemoteAdvertisement;
import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.data.FileMode;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.id.PackChecksum;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.schema.config.BootstrapSourceConfig;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider.SyncStatus.*;
import pro.deta.orion.test.integration.git.FileTestSupport;

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
        FileTestSupport.Prepared update = FileTestSupport.prepared(repository.files(), location.refName(),
                "proxy update", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("orion.xml", "from proxy".getBytes());
            fileAccess.write("run.sh", FileMode.EXECUTABLE_FILE, "#!/bin/sh\n".getBytes());
            fileAccess.write("link", FileMode.SYMLINK, "run.sh".getBytes());
            return null;
        });
        BootstrapGitRuntimeProxy proxy = new BootstrapGitRuntimeProxy(
                location,
                repository,
                new BootstrapGitTransportFactory(new BootstrapSecretResolver(Map.of()), ignored -> List.of()),
                fetcher,
                new NativeBootstrapGitPusher());

        List<RefUpdateResult> results = proxy.publish(
                ingest(repository, update),
                update.refUpdates(),
                true);

        assertThat(results).extracting(RefUpdateResult::status).containsOnly(RefUpdateResult.Status.APPLIED);
        assertThat(proxy.syncObservation().status()).isEqualTo(SUCCESS);
        assertThat(proxy.syncObservation().observedAt()).isNotNull();
        try (Git bareGit = Git.open(upstream.bare().toFile())) {
            assertThat(repository.refs().get(location.refName()))
                    .isEqualTo(bareGit.getRepository().resolve(location.refName()).name());
            var treeId = bareGit.getRepository().resolve(location.refName() + "^{tree}");
            try (TreeWalk script = TreeWalk.forPath(bareGit.getRepository(), "run.sh", treeId);
                 TreeWalk link = TreeWalk.forPath(bareGit.getRepository(), "link", treeId)) {
                assertThat(script.getRawMode(0)).isEqualTo(FileMode.EXECUTABLE_FILE.code());
                assertThat(link.getRawMode(0)).isEqualTo(FileMode.SYMLINK.code());
                assertThat(bareGit.getRepository().open(link.getObjectId(0)).getBytes())
                        .isEqualTo("run.sh".getBytes());
            }
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
        FileTestSupport.Prepared update = FileTestSupport.prepared(repository.files(), location.refName(),
                "proxy update", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("orion.xml", "proxy change".getBytes());
            return null;
        });
        Optional<PackChecksum> received = ingest(repository, update);
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
                new BootstrapGitTransportFactory(new BootstrapSecretResolver(Map.of()), ignored -> List.of()),
                (selected, transport, target) -> { }, new NativeBootstrapGitPusher());
        assertThat(proxy.publish(received, update.refUpdates(), true)).extracting(RefUpdateResult::status)
                .containsExactly(RefUpdateResult.Status.EXPECTED_OLD_MISMATCH);
        assertThat(proxy.syncObservation().status()).isEqualTo(CONFLICT);
        assertThat(repository.refs()).containsEntry(location.refName(), localOldId);
        assertThat(cloneContent(upstream.bare(), "conflict-checkout")).isEqualTo("upstream change");
        upstream.git().close();
    }

    @Test
    void runtimeRecordsNativePushAuthenticationFailureAndPreservesItsCause() throws Exception {
        BootstrapGitLocation location = location(tempDir.resolve("upstream.git"));
        NativeGitRepository repository = repository(location);
        FileTestSupport.Prepared update = FileTestSupport.prepared(repository.files(), location.refName(),
                "update", GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("orion.xml", new byte[]{1});
            return null;
        });
        var proxy = new BootstrapGitRuntimeProxy(location, repository,
                new BootstrapGitTransportFactory(new BootstrapSecretResolver(Map.of()), ignored -> List.of()),
                (selected, transport, target) -> { },
                (selected, transport, target, received, updates, atomic) -> new NativeBootstrapGitPusher().push(
                        selected, (service, uri, options) -> {
                            throw new GitClientTransportException(GitClientFailure.Kind.AUTHENTICATION_FAILED,
                                    false, "upstream-secret-response");
                        }, target, received, updates, atomic));

        assertThatThrownBy(() -> proxy.publish(ingest(repository, update), update.refUpdates(), true))
                .hasMessageNotContaining("upstream-secret-response")
                .hasCauseInstanceOf(GitClientTransportException.class)
                .cause().hasMessage("upstream-secret-response");
        assertThat(proxy.syncObservation().status()).isEqualTo(AUTHENTICATION_FAILED);
        assertThat(proxy.syncObservation().observedAt()).isNotNull();
    }

    @Test
    void mapsReceiveStatusesToRequestedRefOrder() {
        List<RefUpdate> updates = List.of(
                RefUpdate.fromWire("refs/heads/first", NULL_ID, "1".repeat(40)),
                RefUpdate.fromWire("refs/heads/second", NULL_ID, "2".repeat(40)));
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

    private static Optional<PackChecksum> ingest(
            NativeGitRepository repository, FileTestSupport.Prepared update) throws IOException {
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(update.pack()))) {
            PackMetadata pack = repository.ingest(input);
            return Optional.of(repository.publishPack(pack).packChecksum());
        }
    }
}
