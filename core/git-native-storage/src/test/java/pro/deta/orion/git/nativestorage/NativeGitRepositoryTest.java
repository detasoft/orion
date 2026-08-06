package pro.deta.orion.git.nativestorage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.nativestorage.pack.PackIngestionLimits;
import pro.deta.orion.git.nativestorage.pack.PackIngestionResult;
import pro.deta.orion.git.nativestorage.pack.PackIngestionSession;
import pro.deta.orion.git.nativestorage.pack.PackIngestor;
import pro.deta.orion.git.nativestorage.pack.PackPublicationRequest;
import pro.deta.orion.git.nativestorage.pack.PackPublicationStore;
import pro.deta.orion.git.nativestorage.pack.PublishedPack;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;
import pro.deta.orion.git.nativestorage.upload.GitUploadPackException;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.nativestorage.upload.NativeFetchResponse;
import pro.deta.orion.git.nativestorage.upload.NativeObjectFilter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeGitRepositoryTest {
    private static final String NULL_ID = "0".repeat(40);
    private static final String MAIN_ID = "1".repeat(40);
    private static final PackIngestionLimits LIMITS =
            new PackIngestionLimits(1024 * 1024, 100, 1024 * 1024);

    @Test
    void exposesConfiguredIdentityAndEmptyRefSnapshot() {
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                new LooseObjectStore(),
                "refs/heads/main");

        assertThat(repository.name()).isEqualTo("demo.git");
        assertThat(repository.defaultHead()).isEqualTo("refs/heads/main");
        assertThat(repository.refs()).isEmpty();
    }

    @Test
    void exposesUpdatedRefsThroughFreshSnapshots() {
        LooseRefStore refs = new LooseRefStore();
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                refs,
                new LooseObjectStore(),
                "refs/heads/main");

        refs.update("refs/heads/main", NULL_ID, MAIN_ID);

        assertThat(repository.refs())
                .containsExactlyEntriesOf(
                        java.util.Map.of("refs/heads/main", MAIN_ID));
    }

    @Test
    void publishesQuarantinedObjectsWhenRefUpdatesApply() {
        LooseObjectStore publishedObjects = new LooseObjectStore();
        LooseObjectStore quarantine = new LooseObjectStore();
        GitObjectId blob = quarantine.write(
                ObjectType.BLOB,
                "published".getBytes(StandardCharsets.UTF_8));
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                publishedObjects,
                "refs/heads/main");

        List<RefUpdateResult> results = repository.publishObjectsAndRefs(
                quarantine,
                List.of(new LooseRefStore.Update(
                        "refs/heads/main",
                        NULL_ID,
                        blob.value())));

        assertThat(results).containsExactly(RefUpdateResult.CREATED);
        assertThat(repository.refs())
                .containsEntry("refs/heads/main", blob.value());
        assertThat(repository.readObject(blob)).isPresent();
    }

    @Test
    void buildsPackWithoutObjectsReachableFromHaves() {
        LooseObjectStore objects = new LooseObjectStore();
        GitObjectId wanted = objects.write(
                ObjectType.BLOB,
                "wanted".getBytes(StandardCharsets.UTF_8));
        GitObjectId have = objects.write(
                ObjectType.BLOB,
                "already present".getBytes(StandardCharsets.UTF_8));
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                objects,
                "refs/heads/main");

        CompositeByteBuf pack = produce(repository.fetch(
                new NativeFetchRequest(
                Set.of(wanted, have),
                Set.of(have),
                true,
                true,
                true,
                false)));

        try {
            assertThat(pack.getCharSequence(
                    0,
                    4,
                    StandardCharsets.US_ASCII))
                    .hasToString("PACK");
            assertThat(pack.getInt(8)).isEqualTo(1);
        } finally {
            pack.release();
        }
    }

    @Test
    void includesAnnotatedTagWhoseTargetIsSent() {
        LooseRefStore refs = new LooseRefStore();
        LooseObjectStore objects = new LooseObjectStore();
        GitObjectId blob = objects.write(
                ObjectType.BLOB,
                "tagged".getBytes(StandardCharsets.UTF_8));
        GitObjectId tag = objects.write(
                ObjectType.TAG,
                ("object " + blob + "\n"
                        + "type blob\n"
                        + "tag v1\n"
                        + "tagger Test <test@example.com> 0 +0000\n"
                        + "\nmessage\n")
                        .getBytes(StandardCharsets.UTF_8));
        refs.update("refs/tags/v1", NULL_ID, tag.value());
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                refs,
                objects,
                "refs/heads/main");

        CompositeByteBuf pack = produce(repository.fetch(
                new NativeFetchRequest(
                        Set.of(blob),
                        Set.of(),
                        true,
                        false,
                        false,
                        true)));

        try {
            assertThat(pack.getInt(8)).isEqualTo(2);
        } finally {
            pack.release();
        }
    }

    @Test
    void fetchResponseCarriesShallowBoundaryMetadata() {
        LooseObjectStore objects = new LooseObjectStore();
        GitObjectId baseBlob = objects.write(
                ObjectType.BLOB,
                "base".getBytes(StandardCharsets.UTF_8));
        GitObjectId baseTree = objects.write(
                ObjectType.TREE,
                treeEntry("100644", "file.txt", baseBlob));
        GitObjectId baseCommit = writeCommit(objects, baseTree, null, "base");
        GitObjectId tipBlob = objects.write(
                ObjectType.BLOB,
                "tip".getBytes(StandardCharsets.UTF_8));
        GitObjectId tipTree = objects.write(
                ObjectType.TREE,
                treeEntry("100644", "file.txt", tipBlob));
        GitObjectId tipCommit = writeCommit(objects, tipTree, baseCommit, "tip");
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                objects,
                "refs/heads/main");

        NativeFetchResponse response = repository.fetchResponse(
                new NativeFetchRequest(
                        Set.of(tipCommit),
                        Set.of(),
                        true,
                        false,
                        false,
                        false,
                        false,
                        1));
        CompositeByteBuf pack = produce(response.packProducer());

        try {
            assertThat(response.shallowBoundaries())
                    .containsExactly(tipCommit);
            assertThat(pack.getInt(8)).isEqualTo(3);
        } finally {
            pack.release();
        }
    }

    @Test
    void filteredFetchBuildsPackWithoutTreeReachedBlobs() {
        LooseObjectStore objects = new LooseObjectStore();
        GitObjectId blob = objects.write(
                ObjectType.BLOB,
                "filtered".getBytes(StandardCharsets.UTF_8));
        GitObjectId tree = objects.write(
                ObjectType.TREE,
                treeEntry("100644", "file.txt", blob));
        GitObjectId commit = writeCommit(objects, tree, null, "filtered");
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                objects,
                "refs/heads/main");

        NativeFetchResponse response = repository.fetchResponse(
                new NativeFetchRequest(
                        Set.of(commit),
                        Set.of(),
                        true,
                        false,
                        false,
                        false,
                        false,
                        0,
                        NativeObjectFilter.BLOB_NONE));
        CompositeByteBuf pack = produce(response.packProducer());

        try {
            assertThat(pack.getInt(8)).isEqualTo(2);
        } finally {
            pack.release();
        }
    }

    @Test
    void fetchBuildsServerPackInsteadOfReturningReceivedPackBytes() {
        LooseRefStore refs = new LooseRefStore();
        LooseObjectStore objects = new LooseObjectStore();
        RecordingPackPublicationStore publicationStore =
                new RecordingPackPublicationStore();
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                refs,
                objects,
                "refs/heads/main",
                publicationStore);
        PackBlob first = blob("first from client".getBytes(StandardCharsets.UTF_8));
        PackBlob second = blob("second from client".getBytes(StandardCharsets.UTF_8));
        List<PackBlob> incomingOrder = new ArrayList<>(List.of(first, second));
        incomingOrder.sort((left, right) ->
                right.objectId().value().compareTo(left.objectId().value()));
        byte[] receivedPack = pack(incomingOrder);
        PackIngestionResult.Complete complete =
                complete(accept(repository.beginPackIngestion(LIMITS), receivedPack));
        repository.publishObjectsAndRefs(
                complete.quarantine(),
                List.of(new LooseRefStore.Update(
                        "refs/heads/main",
                        NULL_ID,
                        first.objectId().value())));

        CompositeByteBuf fetchPack = produce(repository.fetch(
                new NativeFetchRequest(
                        Set.of(first.objectId(), second.objectId()),
                        Set.of(),
                        true,
                        true,
                        true,
                        false)));

        try {
            byte[] generatedPack = ByteBufUtil.getBytes(fetchPack);
            LooseObjectStore fetchedObjects = ingest(generatedPack);

            assertThat(publicationStore.packBytes()).isEqualTo(receivedPack);
            assertThat(generatedPack).isNotEqualTo(receivedPack);
            assertThat(fetchedObjects.contains(first.objectId())).isTrue();
            assertThat(fetchedObjects.contains(second.objectId())).isTrue();
        } finally {
            fetchPack.release();
        }
    }

    @Test
    void resolvesWantedRefsIntoPackAndResponseMetadata() {
        LooseRefStore refs = new LooseRefStore();
        LooseObjectStore objects = new LooseObjectStore();
        GitObjectId blob = objects.write(
                ObjectType.BLOB,
                "wanted".getBytes(StandardCharsets.UTF_8));
        refs.update("refs/heads/main", NULL_ID, blob.value());
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                refs,
                objects,
                "refs/heads/main");

        NativeFetchResponse response = repository.fetchResponse(
                new NativeFetchRequest(
                        Set.of(),
                        Set.of(),
                        true,
                        false,
                        false,
                        false,
                        false,
                        0,
                        NativeObjectFilter.NONE,
                        Set.of("refs/heads/main")));
        CompositeByteBuf pack = produce(response.packProducer());

        try {
            assertThat(response.wantedRefs())
                    .containsExactlyEntriesOf(
                            java.util.Map.of("refs/heads/main", blob));
            assertThat(pack.getInt(8)).isEqualTo(1);
        } finally {
            pack.release();
        }
    }

    @Test
    void rejectsMissingWantedRefBeforePackBuild() {
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                new LooseObjectStore(),
                "refs/heads/main");

        assertThatThrownBy(() -> repository.fetchResponse(
                new NativeFetchRequest(
                        Set.of(),
                        Set.of(),
                        true,
                        false,
                        false,
                        false,
                        false,
                        0,
                        NativeObjectFilter.NONE,
                        Set.of("refs/heads/missing"))))
                .isInstanceOfSatisfying(
                        GitUploadPackException.class,
                        error -> assertThat(error.kind())
                                .isEqualTo(
                                        GitUploadPackException.Kind
                                                .MISSING_REF));
    }

    private static CompositeByteBuf produce(
            NativePackProducer producer) {
        CompositeByteBuf complete = Unpooled.compositeBuffer();
        try (producer) {
            while (true) {
                ByteBuf fragment = Unpooled.buffer(3, 3);
                NativePackProducer.Result result;
                try {
                    result = producer.produce(fragment);
                    complete.addComponent(
                            true,
                            fragment.retain());
                } finally {
                    fragment.release();
                }
                if (result == NativePackProducer.Result.COMPLETED) {
                    return complete;
                }
            }
        } catch (RuntimeException error) {
            complete.release();
            throw error;
        }
    }

    private static PackIngestionResult accept(
            PackIngestionSession session,
            byte[] bytes) {
        ByteBuf input = Unpooled.wrappedBuffer(bytes);
        try {
            PackIngestionResult result = session.accept(input);
            if (result instanceof PackIngestionResult.NeedInput) {
                return session.endOfInput();
            }
            return result;
        } finally {
            input.release();
        }
    }

    private static PackIngestionResult.Complete complete(
            PackIngestionResult result) {
        assertThat(result).isInstanceOf(PackIngestionResult.Complete.class);
        return (PackIngestionResult.Complete) result;
    }

    private static LooseObjectStore ingest(byte[] pack) {
        ByteBuf input = Unpooled.wrappedBuffer(pack);
        try {
            return new PackIngestor(pack.length).ingest(input);
        } finally {
            input.release();
        }
    }

    private static GitObjectId writeCommit(
            LooseObjectStore objects,
            GitObjectId tree,
            GitObjectId parent,
            String message) {
        StringBuilder data = new StringBuilder()
                .append("tree ")
                .append(tree)
                .append('\n');
        if (parent != null) {
            data.append("parent ").append(parent).append('\n');
        }
        data.append("author Test <test@example.com> 0 +0000\n")
                .append("committer Test <test@example.com> 0 +0000\n")
                .append('\n')
                .append(message)
                .append('\n');
        return objects.write(
                ObjectType.COMMIT,
                data.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] treeEntry(
            String mode,
            String name,
            GitObjectId objectId) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes((mode + " " + name + "\0")
                .getBytes(StandardCharsets.UTF_8));
        output.writeBytes(HexFormat.of().parseHex(objectId.value()));
        return output.toByteArray();
    }

    private static PackBlob blob(byte[] data) {
        return new PackBlob(blobId(data), data);
    }

    private static GitObjectId blobId(byte[] data) {
        byte[] header = ("blob " + data.length + "\0")
                .getBytes(StandardCharsets.US_ASCII);
        MessageDigest digest = sha1();
        digest.update(header);
        digest.update(data);
        return GitObjectId.of(HexFormat.of().formatHex(digest.digest()));
    }

    private static byte[] pack(List<PackBlob> objects) {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeInt(body, 0x5041434b);
            writeInt(body, 2);
            writeInt(body, objects.size());
            for (PackBlob object : objects) {
                writeBlobObject(body, object.data());
            }
            byte[] bodyBytes = body.toByteArray();
            ByteArrayOutputStream pack = new ByteArrayOutputStream();
            pack.writeBytes(bodyBytes);
            pack.writeBytes(sha1(bodyBytes));
            return pack.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void writeBlobObject(
            ByteArrayOutputStream output,
            byte[] data) throws IOException {
        int size = data.length;
        int first = (ObjectType.BLOB.packTypeId() << 4) | (size & 0x0f);
        size >>>= 4;
        if (size != 0) {
            first |= 0x80;
        }
        output.write(first);
        while (size != 0) {
            int next = size & 0x7f;
            size >>>= 7;
            if (size != 0) {
                next |= 0x80;
            }
            output.write(next);
        }
        try (DeflaterOutputStream deflater =
                     new DeflaterOutputStream(output)) {
            deflater.write(data);
        }
    }

    private static void writeInt(ByteArrayOutputStream output, int value) {
        output.write(value >>> 24);
        output.write(value >>> 16);
        output.write(value >>> 8);
        output.write(value);
    }

    private static byte[] sha1(byte[] bytes) {
        return sha1().digest(bytes);
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-1 not available", error);
        }
    }

    private record PackBlob(GitObjectId objectId, byte[] data) {
        private PackBlob {
            data = data.clone();
        }

        @Override
        public byte[] data() {
            return data.clone();
        }
    }

    private static final class RecordingPackPublicationStore
            implements PackPublicationStore {
        private byte[] packBytes;

        @Override
        public Optional<PublishedPack> publish(
                PackPublicationRequest request) {
            packBytes = request.packBytes();
            return Optional.of(new PublishedPack(
                    request.packId(),
                    request.packBytes().length,
                    request.objectCount(),
                    request.packId(),
                    request.indexId()));
        }

        private byte[] packBytes() {
            return packBytes.clone();
        }
    }
}
