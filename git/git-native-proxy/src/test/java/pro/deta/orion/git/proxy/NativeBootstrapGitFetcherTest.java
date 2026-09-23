package pro.deta.orion.git.proxy;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectInserter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.client.GitClientTransport;
import pro.deta.orion.git.client.GitClientTransportSession;
import pro.deta.orion.git.client.GitFileClientTransport;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.GitFile;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.pack.PackWriter;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.BufferedByteOutput;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;
import pro.deta.orion.schema.config.BootstrapSourceConfig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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
            AtomicInteger connections = new AtomicInteger();
            GitClientTransport discoveryOnly = (service, uri, options) -> {
                assertThat(connections.incrementAndGet()).as("only discover; no pack download").isEqualTo(1);
                return new GitFileClientTransport().open(service, uri, options);
            };
            fetcher.fetch(location, discoveryOnly, repository);

            assertThat(repository.refs()).containsEntry(location.refName(), firstId);
            assertThat(repository.loadFiles(location.refName(), List.of("orion.xml")).files())
                    .containsEntry("orion.xml", GitFile.regular("first".getBytes()));
        } finally {
            upstream.git().close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ffffffffffffffffffffffffffffffffffffffff", "invalid-private-tree"})
    void rejectsInvalidDownloadedObjectGraphBeforeRefPublication(String tree) throws Exception {
        NativeGitRepository repository = new InMemoryNativeGitRepositoryProvider()
                .create("proxy").valueOrFailure("create proxy");
        byte[] content = ("tree " + tree + "\n\ninvalid tree\n").getBytes(StandardCharsets.UTF_8);
        ObjectId commit;
        try (ObjectInserter.Formatter formatter = new ObjectInserter.Formatter()) {
            commit = new ObjectId(formatter.idFor(Constants.OBJ_COMMIT, content).name());
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PackWriter writer = new PackWriter(new OutputStreamBufferedByteOutput(bytes), 1);
             BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(content))) {
            writer.writeObject(GitObjectType.COMMIT, content.length, input);
            writer.finish();
        }
        GitClientTransport transport = packTransport(commit, bytes.toByteArray());

        assertThatThrownBy(() -> new NativeBootstrapGitFetcher().fetch(
                location(tempDir.resolve("upstream.git")), transport, repository))
                .isInstanceOf(BootstrapGitProxyException.class)
                .hasMessage("Remote Git bootstrap failed during complete object validation");
        assertThat(repository.readObject(commit)).isPresent();
        assertThat(repository.refs()).isEmpty();
    }

    @Test
    void reportsConcurrentLocalRefPublicationAsAConflictWithoutChangingRefs() throws Exception {
        Upstream upstream = upstream("remote");
        BootstrapGitLocation location = location(upstream.bare());
        NativeGitRepository repository = new InMemoryNativeGitRepositoryProvider()
                .create("proxy").valueOrFailure("create proxy");
        repository.saveFiles("refs/heads/main", Map.of("orion.xml", GitFile.regular(new byte[]{1})), Set.of(),
                "local", GitCommitAuthor.EMPTY);
        repository.saveFiles("refs/heads/incoming", Map.of("orion.xml", GitFile.regular(new byte[]{2})), Set.of(),
                "remote", GitCommitAuthor.EMPTY);
        Map<String, String> before = repository.refs();
        String concurrent = before.get("refs/heads/incoming");
        AtomicInteger connections = new AtomicInteger();
        GitClientTransport transport = (service, uri, options) -> {
            if (connections.incrementAndGet() == 2) {
                repository.publishRefs(List.of(RefUpdate.fromWire(
                        location.refName(), before.get(location.refName()), concurrent)), true);
            }
            return new GitFileClientTransport().open(service, uri, options);
        };

        try {
            assertThatThrownBy(() -> new NativeBootstrapGitFetcher().fetch(location, transport, repository))
                    .isInstanceOfSatisfying(BootstrapGitProxyException.class, failure ->
                            assertThat(failure.status())
                                    .isEqualTo(ProxyAwareNativeGitRepositoryProvider.SyncStatus.CONFLICT));
            assertThat(repository.refs()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "refs/heads/main", concurrent, "refs/heads/incoming", concurrent));
        } finally {
            upstream.git().close();
        }
    }

    private static GitClientTransport packTransport(ObjectId commit, byte[] pack) throws IOException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        BufferedByteOutput output = new OutputStreamBufferedByteOutput(response);
        new GitPktLine.Data((commit.toHex() + " refs/heads/main\n")
                .getBytes(StandardCharsets.US_ASCII)).writeTo(output);
        GitPktLine.Control.FLUSH.writeTo(output);
        new GitPktLine.Data("NAK\n".getBytes(StandardCharsets.US_ASCII)).writeTo(output);
        output.write(pack);
        byte[] bytes = response.toByteArray();
        return (service, uri, options) -> new GitClientTransportSession() {
            private final BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(bytes));
            private final BufferedByteOutput sink = new OutputStreamBufferedByteOutput(OutputStream.nullOutputStream());

            @Override
            public BufferedByteInputV2 input() {
                return input;
            }

            @Override
            public BufferedByteOutput output() {
                return sink;
            }

            @Override
            public void close() throws IOException {
                input.close();
            }
        };
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
