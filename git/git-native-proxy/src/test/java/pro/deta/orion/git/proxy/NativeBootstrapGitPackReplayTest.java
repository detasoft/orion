package pro.deta.orion.git.proxy;

import io.netty.buffer.ByteBuf;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.pack.DeltaEncoder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.client.GitClientTransport;
import pro.deta.orion.git.client.GitClientTransportSession;
import pro.deta.orion.git.client.GitFileClientTransport;
import pro.deta.orion.git.nativestorage.FileNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.pack.PackWriter;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.BufferedByteOutput;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;
import pro.deta.orion.schema.config.BootstrapSourceConfig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class NativeBootstrapGitPackReplayTest {
    private static final String ZERO = "0".repeat(40);
    private static final String REF = "refs/tags/payload";

    @TempDir
    private Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replaysCompletedPackWithItsBaseRegardlessOfUpstreamHistory(boolean disk) throws Exception {
        for (boolean upstreamHasBase : List.of(true, false)) {
            Path bare = directory.resolve("upstream-" + upstreamHasBase + ".git");
            NativeGitRepository repository = disk
                    ? new FileNativeGitRepositoryProvider(directory.resolve("cache-" + upstreamHasBase))
                            .create("proxy").valueOrFailure("repository")
                    : new NativeGitRepository("proxy", new GitStorageApi(), "refs/heads/main");
            byte[] base = new byte[8192];
            new Random(37).nextBytes(base);
            byte[] target = base.clone();
            target[4096] ^= 1;
            pro.deta.orion.git.parser.v2.id.ObjectId baseId = repository.writeObject(GitObjectType.BLOB, base);
            pro.deta.orion.git.parser.v2.id.ObjectId targetId = repository.writeObject(GitObjectType.BLOB, target);
            ByteArrayOutputStream delta = new ByteArrayOutputStream();
            DeltaEncoder encoder = new DeltaEncoder(delta, base.length, target.length);
            encoder.copy(0, 4096);
            encoder.insert(new byte[]{target[4096]});
            encoder.copy(4097, target.length - 4097);
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
                deflater.write(delta.toByteArray());
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (PackWriter writer = new PackWriter(new OutputStreamBufferedByteOutput(bytes), 1);
                 BufferedByteInputV2 input = new BufferedByteInputV2(
                         new ByteArrayInputStream(compressed.toByteArray()))) {
                writer.writeCompressed(GitObjectType.REF_DELTA, delta.size(),
                        Optional.of(new pro.deta.orion.git.parser.v2.id.ObjectId(baseId.toHex())), input);
                writer.finish();
            }
            byte[] original = bytes.toByteArray();
            Optional<PackId> received = ingest(repository, original);
            byte[] completed;
            try (IndexedPack pack = repository.storage().openPack(received.orElseThrow()).orElseThrow();
                 BufferedByteInputV2 input = pack.input()) {
                completed = input.newInputStream().readAllBytes();
                assertThat(pack.find(new pro.deta.orion.git.parser.v2.id.ObjectId(baseId.toHex()))).isPresent();
            }

            try (Git upstream = Git.init().setDirectory(bare.toFile()).setBare(true).call()) {
                if (upstreamHasBase) {
                    try (var inserter = upstream.getRepository().newObjectInserter()) {
                        ObjectId id = inserter.insert(Constants.OBJ_BLOB, base);
                        inserter.flush();
                        var ref = upstream.getRepository().updateRef(REF);
                        ref.setNewObjectId(id);
                        ref.update();
                    }
                }
                ByteArrayOutputStream sent = new ByteArrayOutputStream();
                List<RefUpdate> updates = List.of(RefUpdate.fromWire(
                        REF, upstreamHasBase ? baseId.toHex() : ZERO, targetId.toHex()));

                assertThat(new NativeBootstrapGitPusher().push(location(bare), recordingTransport(sent),
                        repository, received, updates, true)).containsExactly(true);

                byte[] forwarded = packFrom(sent.toByteArray());
                assertThat(forwarded).isEqualTo(completed);
                assertThat(upstream.getRepository().resolve(REF).name()).isEqualTo(targetId.toHex());
                assertThat(upstream.getRepository().open(ObjectId.fromString(targetId.toHex())).getBytes())
                        .isEqualTo(target);
            }
        }
    }

    @Test
    void buildsMissingObjectsWhenIncomingPackDoesNotCoverTheRequestedCommit() throws Exception {
        NativeGitRepository repository = new NativeGitRepository(
                    "proxy", new GitStorageApi(), "refs/heads/main");
        repository.saveFiles("main", Map.of("config.txt", new byte[]{1}), "local", GitCommitAuthor.EMPTY);
        String commit = repository.refs().get("refs/heads/main");
        var unrelated = repository.prepareFileUpdate("other", Map.of("other.txt", new byte[]{2}),
                "unrelated", GitCommitAuthor.EMPTY);
        Optional<PackId> received = ingest(repository, unrelated.pack());
        Path bare = directory.resolve("missing-objects.git");

        try (Git upstream = Git.init().setDirectory(bare.toFile()).setBare(true).call()) {
            assertThat(new NativeBootstrapGitPusher().push(location(bare), new GitFileClientTransport(),
                    repository, received,
                    List.of(RefUpdate.fromWire("refs/heads/main", ZERO, commit)), true))
                    .containsExactly(true);
            assertThat(upstream.getRepository().resolve("refs/heads/main").name()).isEqualTo(commit);
        }
    }

    private static Optional<PackId> ingest(NativeGitRepository repository, byte[] bytes) throws IOException {
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(bytes))) {
            IndexedPack pack = repository.ingest(input);
            return Optional.of(repository.storage().persist(pack));
        }
    }

    private static GitClientTransport recordingTransport(ByteArrayOutputStream sent) {
        return (service, uri, options) -> {
            GitClientTransportSession delegate = new GitFileClientTransport().open(service, uri, options);
            return new GitClientTransportSession() {
                @Override
                public BufferedByteInputV2 input() {
                    return delegate.input();
                }

                @Override
                public BufferedByteOutput output() {
                    return new BufferedByteOutput() {
                        @Override
                        public void write(ByteBuf buffer) throws IOException {
                            byte[] bytes = new byte[buffer.readableBytes()];
                            buffer.getBytes(buffer.readerIndex(), bytes);
                            sent.writeBytes(bytes);
                            delegate.output().write(buffer);
                        }

                        @Override
                        public void flush() throws IOException {
                            delegate.output().flush();
                        }
                    };
                }

                @Override
                public void close() throws IOException {
                    delegate.close();
                }
            };
        };
    }

    private static byte[] packFrom(byte[] request) {
        for (int index = 0; index + 4 <= request.length; index++) {
            if (request[index] == 'P' && request[index + 1] == 'A'
                    && request[index + 2] == 'C' && request[index + 3] == 'K') {
                return Arrays.copyOfRange(request, index, request.length);
            }
        }
        throw new AssertionError("No pack was sent");
    }

    private static BootstrapGitLocation location(Path bare) {
        BootstrapSourceConfig source = new BootstrapSourceConfig();
        source.setLocation("git+" + bare.toUri());
        source.setRef("refs/heads/main");
        source.setPath("config.txt");
        return BootstrapGitLocation.parse(source);
    }

}
