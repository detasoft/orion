package pro.deta.orion.git.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.client.GitClientTransport;
import pro.deta.orion.git.client.GitClientTransportSession;
import pro.deta.orion.git.client.GitFileClientTransport;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.pack.DeltaPackBuilder;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.nativestorage.pack.PackIngestionLimits;
import pro.deta.orion.git.nativestorage.pack.PackIngestionResult;
import pro.deta.orion.git.nativestorage.pack.PackIngestor;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.BufferedByteOutput;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;
import pro.deta.orion.schema.config.BootstrapSourceConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class NativeBootstrapGitPackReplayTest {
    private static final String ZERO = "0".repeat(40);
    private static final String REF = "refs/tags/payload";

    @TempDir
    private Path directory;

    @Test
    void replaysThinPackOnlyWhenItsExternalBaseIsKnownUpstream() throws Exception {
        for (boolean upstreamHasBase : List.of(true, false)) {
            Path bare = directory.resolve("upstream-" + upstreamHasBase + ".git");
            CountingRepository repository = new CountingRepository();
            byte[] base = new byte[8192];
            new Random(37).nextBytes(base);
            byte[] target = base.clone();
            target[4096] ^= 1;
            GitObjectId baseId = repository.writeObject(ObjectType.BLOB, base);
            GitObjectId targetId = repository.writeObject(ObjectType.BLOB, target);
            byte[] original;
            try (NativePackProducer producer = new DeltaPackBuilder()
                    .producer(repository::readObject, List.of(targetId), List.of(baseId))) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                producer.writeTo(new OutputStreamBufferedByteOutput(bytes));
                original = bytes.toByteArray();
            }
            PackIngestionResult.Complete received = ingest(repository, original);
            assertThat(received.externalBaseIds()).containsExactly(baseId);
            byte[] alteredRead = received.packBytes();
            alteredRead[0] = 0;
            assertThat(received.packBytes()).isEqualTo(original);

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
                List<LooseRefStore.Update> updates = List.of(new LooseRefStore.Update(
                        REF, upstreamHasBase ? baseId.value() : ZERO, targetId.value()));

                assertThat(new NativeBootstrapGitPusher().push(location(bare), recordingTransport(sent),
                        repository, received, updates, true)).containsExactly(true);

                byte[] forwarded = packFrom(sent.toByteArray());
                if (upstreamHasBase) {
                    assertThat(forwarded).isEqualTo(original);
                    assertThat(repository.rebuiltPacks).isZero();
                } else {
                    assertThat(forwarded).isNotEqualTo(original);
                    assertThat(repository.rebuiltPacks).isEqualTo(1);
                }
                assertThat(upstream.getRepository().resolve(REF).name()).isEqualTo(targetId.value());
                assertThat(upstream.getRepository().open(ObjectId.fromString(targetId.value())).getBytes())
                        .isEqualTo(target);
            }
        }
    }

    @Test
    void buildsMissingObjectsWhenIncomingPackDoesNotCoverTheRequestedCommit() throws Exception {
        CountingRepository repository = new CountingRepository();
        repository.saveFiles("main", Map.of("config.txt", new byte[]{1}), "local", GitCommitAuthor.EMPTY);
        String commit = repository.refs().get("refs/heads/main");
        var unrelated = repository.prepareFileUpdate("other", Map.of("other.txt", new byte[]{2}),
                "unrelated", GitCommitAuthor.EMPTY);
        PackIngestionResult.Complete received = ingest(repository, unrelated.pack());
        Path bare = directory.resolve("missing-objects.git");

        try (Git upstream = Git.init().setDirectory(bare.toFile()).setBare(true).call()) {
            assertThat(new NativeBootstrapGitPusher().push(location(bare), new GitFileClientTransport(),
                    repository, received,
                    List.of(new LooseRefStore.Update("refs/heads/main", ZERO, commit)), true))
                    .containsExactly(true);
            assertThat(repository.rebuiltPacks).isEqualTo(1);
            assertThat(upstream.getRepository().resolve("refs/heads/main").name()).isEqualTo(commit);
        }
    }

    private static PackIngestionResult.Complete ingest(NativeGitRepository repository, byte[] bytes) {
        ByteBuf input = Unpooled.wrappedBuffer(bytes);
        try (PackIngestor ingestor = new PackIngestor(
                new PackIngestionLimits(bytes.length, 100, 1024 * 1024), repository::readObject)) {
            return (PackIngestionResult.Complete) ingestor.accept(input);
        } finally {
            input.release();
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

    private static final class CountingRepository extends NativeGitRepository {
        private int rebuiltPacks;

        private CountingRepository() {
            super("proxy", new LooseRefStore(), new LooseObjectStore(), "refs/heads/main");
        }

        @Override
        public NativePackProducer fetch(NativeFetchRequest request) {
            rebuiltPacks++;
            return super.fetch(request);
        }
    }
}
