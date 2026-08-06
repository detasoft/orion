package pro.deta.orion.git.nativestorage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.pack.PackIngestionLimits;
import pro.deta.orion.git.nativestorage.pack.PackIngestionResult;
import pro.deta.orion.git.nativestorage.pack.PackIngestionSession;
import pro.deta.orion.git.nativestorage.pack.PublishedPackContent;
import pro.deta.orion.util.Result;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class FileNativeGitRepositoryProviderTest {
    private static final String NULL_ID = "0".repeat(40);
    private static final PackIngestionLimits LIMITS =
            new PackIngestionLimits(1024 * 1024, 100, 1024 * 1024);

    @Test
    void reopensPersistedRefsAndObjects(@TempDir Path rootDirectory) {
        FileNativeGitRepositoryProvider first =
                new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository repository = first.create(
                "team/project.git").valueOrFailure("repository");
        GitObjectId blob = repository.writeObject(
                ObjectType.BLOB,
                "persistent".getBytes(StandardCharsets.UTF_8));
        repository.updateRef("refs/heads/main", NULL_ID, blob.value());

        FileNativeGitRepositoryProvider second =
                new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository reopened = second.find("team/project.git")
                .valueOrFailure("repository");

        assertThat(reopened.name()).isEqualTo("team/project.git");
        assertThat(reopened.defaultHead()).isEqualTo("refs/heads/main");
        assertThat(reopened.refs())
                .containsEntry("refs/heads/main", blob.value());
        assertThat(reopened.readObject(blob))
                .isPresent()
                .get()
                .satisfies(object -> {
                    assertThat(object.type()).isEqualTo(ObjectType.BLOB);
                    assertThat(object.data()).isEqualTo(
                            "persistent".getBytes(StandardCharsets.UTF_8));
                });
    }

    @Test
    void repositoryNamesDoNotMapDirectlyToFileSystemPaths(
            @TempDir Path rootDirectory) {
        FileNativeGitRepositoryProvider provider =
                new FileNativeGitRepositoryProvider(rootDirectory);

        provider.create("team/project.git")
                .valueOrFailure("repository");

        assertThat(Files.exists(rootDirectory.resolve("team"))).isFalse();
    }

    @Test
    void createFailsWhenRepositoryAlreadyExists(@TempDir Path rootDirectory) {
        FileNativeGitRepositoryProvider provider =
                new FileNativeGitRepositoryProvider(rootDirectory);
        provider.create("project.git").valueOrFailure("repository");

        Result<NativeGitRepository> result = provider.create("project.git");

        assertThat(result).isInstanceOf(Result.Failure.class);
        assertThat(((Result.Failure<?>) result).code())
                .isEqualTo(Result.FailureCode.FILE_ALREADY_EXISTS);
    }

    @Test
    void findDoesNotCreateRepository(@TempDir Path rootDirectory) {
        FileNativeGitRepositoryProvider provider =
                new FileNativeGitRepositoryProvider(rootDirectory);

        Result<NativeGitRepository> result = provider.find("missing.git");

        assertThat(result).isInstanceOf(Result.Failure.class);
        assertThat(provider.exists("missing.git")).isFalse();
    }

    @Test
    void publishesReceivedPackWithDurableIndexAndManifest(
            @TempDir Path rootDirectory) throws IOException {
        FileNativeGitRepositoryProvider provider =
                new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository repository = provider.create("team/project.git")
                .valueOrFailure("repository");
        byte[] first = "published-one".getBytes(StandardCharsets.UTF_8);
        byte[] second = "published-two".getBytes(StandardCharsets.UTF_8);
        byte[] pack = pack(first, second);
        List<String> objectIds = sortedIds(blobId(first), blobId(second));
        String packId = packChecksum(pack);

        PackIngestionResult result =
                accept(repository.beginPackIngestion(LIMITS), pack);

        assertThat(result).isInstanceOf(PackIngestionResult.Complete.class);
        PackIngestionResult.Complete complete =
                (PackIngestionResult.Complete) result;
        assertThat(complete.publishedPack()).isPresent();
        assertThat(complete.publishedPack().get().packId()).isEqualTo(packId);
        assertThat(complete.publishedPack().get().packBytes())
                .isEqualTo(pack.length);
        assertThat(complete.publishedPack().get().objectCount())
                .isEqualTo(objectIds.size());
        Path packFile = singlePathWithSuffix(rootDirectory, packId + ".pack");
        Path indexFile = singlePathWithSuffix(rootDirectory, packId + ".idx");
        Path manifestFile = singlePathWithSuffix(rootDirectory, packId + ".json");

        assertThat(Files.readAllBytes(packFile)).isEqualTo(pack);
        assertPackIndex(Files.readAllBytes(indexFile), pack, objectIds);
        String manifest = Files.readString(manifestFile, StandardCharsets.UTF_8);
        assertThat(manifest)
                .contains("\"visibility\": \"PUBLISHED\"")
                .contains("\"source\": \"receive-pack\"")
                .contains("\"selfContained\": true")
                .contains(packId);
        for (String objectId : objectIds) {
            assertThat(manifest).contains(objectId);
        }
        assertThat(repository.publishedPacks())
                .singleElement()
                .satisfies(published -> {
                    assertThat(published.packId()).isEqualTo(packId);
                    assertThat(published.selfContained()).isTrue();
                    assertThat(published.objectIds())
                            .extracting(GitObjectId::value)
                            .containsExactlyElementsOf(objectIds);
                });
        try (PublishedPackContent content =
                     repository.openPublishedPack(packId).orElseThrow()) {
            assertThat(content.manifest().packId()).isEqualTo(packId);
            assertThat(content.input().readAllBytes()).isEqualTo(pack);
        }
        assertThat(repository.openPublishedPack("x".repeat(40))).isEmpty();
    }

    @Test
    void readsPublishedPackObjectsThroughPackIndexes(
            @TempDir Path rootDirectory) {
        FileNativeGitRepositoryProvider provider =
                new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository repository = provider.create("team/project.git")
                .valueOrFailure("repository");
        byte[] first = "pack-backed-one".getBytes(StandardCharsets.UTF_8);
        byte[] second = "pack-backed-two".getBytes(StandardCharsets.UTF_8);
        byte[] pack = pack(first, second);

        PackIngestionResult result =
                accept(repository.beginPackIngestion(LIMITS), pack);

        assertThat(result).isInstanceOf(PackIngestionResult.Complete.class);
        assertPublishedObject(repository, blobId(first), first);

        FileNativeGitRepositoryProvider reopenedProvider =
                new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository reopened = reopenedProvider.find("team/project.git")
                .valueOrFailure("repository");
        assertPublishedObject(reopened, blobId(second), second);
        assertThat(reopened.readObjectPrefix(GitObjectId.of(blobId(first)), 9))
                .isPresent()
                .get()
                .satisfies(prefix -> {
                    assertThat(prefix.type()).isEqualTo(ObjectType.BLOB);
                    assertThat(prefix.declaredDataLength())
                            .isEqualTo(first.length);
                    assertThat(prefix.dataPrefix()).isEqualTo(
                            "pack-back".getBytes(StandardCharsets.UTF_8));
                });
    }

    @Test
    void readsPublishedOffsetDeltaObjectsOnDemand(
            @TempDir Path rootDirectory) {
        FileNativeGitRepositoryProvider provider =
                new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository repository = provider.create("team/project.git")
                .valueOrFailure("repository");
        byte[] source = "hello world".getBytes(StandardCharsets.UTF_8);
        byte[] target = "hello native".getBytes(StandardCharsets.UTF_8);
        byte[] pack = packWithOffsetDelta(source, target);

        PackIngestionResult result =
                accept(repository.beginPackIngestion(LIMITS), pack);

        assertThat(result).isInstanceOf(PackIngestionResult.Complete.class);
        assertPublishedObject(repository, blobId(target), target);
        assertThat(repository.publishedPacks())
                .singleElement()
                .satisfies(manifest ->
                        assertThat(manifest.selfContained()).isTrue());

        FileNativeGitRepositoryProvider reopenedProvider =
                new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository reopened = reopenedProvider.find("team/project.git")
                .valueOrFailure("repository");
        assertPublishedObject(reopened, blobId(target), target);
    }

    @Test
    void readsPublishedThinReferenceDeltaObjectsOnDemand(
            @TempDir Path rootDirectory) {
        FileNativeGitRepositoryProvider provider =
                new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository repository = provider.create("team/project.git")
                .valueOrFailure("repository");
        byte[] source = "hello world".getBytes(StandardCharsets.UTF_8);
        byte[] target = "hello native".getBytes(StandardCharsets.UTF_8);
        GitObjectId baseId = repository.writeObject(ObjectType.BLOB, source);
        byte[] pack = packWithReferenceDelta(
                baseId.value(),
                source,
                target);

        PackIngestionResult result =
                accept(repository.beginPackIngestion(LIMITS), pack);

        assertThat(result).isInstanceOf(PackIngestionResult.Complete.class);
        assertPublishedObject(repository, blobId(target), target);
        assertThat(repository.publishedPacks())
                .singleElement()
                .satisfies(manifest -> {
                    assertThat(manifest.selfContained()).isFalse();
                    assertThat(manifest.externalBaseIds()).contains(baseId);
                });

        FileNativeGitRepositoryProvider reopenedProvider =
                new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository reopened = reopenedProvider.find("team/project.git")
                .valueOrFailure("repository");
        assertThat(reopened.readObjectPrefix(GitObjectId.of(blobId(target)), 7))
                .isPresent()
                .get()
                .satisfies(prefix -> {
                    assertThat(prefix.type()).isEqualTo(ObjectType.BLOB);
                    assertThat(prefix.declaredDataLength())
                            .isEqualTo(target.length);
                    assertThat(prefix.dataPrefix()).isEqualTo(
                            "hello n".getBytes(StandardCharsets.UTF_8));
                });
    }

    @Test
    void missingPublishedPackObjectReturnsEmpty(
            @TempDir Path rootDirectory) {
        FileNativeGitRepositoryProvider provider =
                new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository repository = provider.create("team/project.git")
                .valueOrFailure("repository");
        byte[] pack = pack("indexed-object".getBytes(StandardCharsets.UTF_8));

        PackIngestionResult result =
                accept(repository.beginPackIngestion(LIMITS), pack);

        assertThat(result).isInstanceOf(PackIngestionResult.Complete.class);
        GitObjectId missing = GitObjectId.of("f".repeat(40));
        assertThat(repository.readObject(missing)).isEmpty();
        assertThat(repository.readObjectPrefix(missing, 16)).isEmpty();
    }

    @Test
    void malformedReceivedPackDoesNotPublishPackIndexOrManifest(
            @TempDir Path rootDirectory) throws IOException {
        FileNativeGitRepositoryProvider provider =
                new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository repository = provider.create("team/project.git")
                .valueOrFailure("repository");
        byte[] pack = pack("broken".getBytes(StandardCharsets.UTF_8));
        pack[pack.length - 1] ^= 1;

        PackIngestionResult result =
                accept(repository.beginPackIngestion(LIMITS), pack);

        assertThat(result).isInstanceOf(PackIngestionResult.Failed.class);
        assertThat(pathsWithSuffix(rootDirectory, ".pack")).isEmpty();
        assertThat(pathsWithSuffix(rootDirectory, ".idx")).isEmpty();
        assertThat(pathsWithSuffix(rootDirectory, ".json")).isEmpty();
    }

    private static void assertPublishedObject(
            NativeGitRepository repository,
            String objectId,
            byte[] expectedData) {
        assertThat(repository.readObject(GitObjectId.of(objectId)))
                .isPresent()
                .get()
                .satisfies(object -> {
                    assertThat(object.type()).isEqualTo(ObjectType.BLOB);
                    assertThat(object.data()).isEqualTo(expectedData);
                });
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

    private static byte[] pack(byte[]... objects) {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeInt(body, 0x5041434b);
            writeInt(body, 2);
            writeInt(body, objects.length);
            for (byte[] object : objects) {
                writeObject(body, object);
            }
            return withChecksum(body.toByteArray());
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static byte[] packWithOffsetDelta(
            byte[] source,
            byte[] target) {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeInt(body, 0x5041434b);
            writeInt(body, 2);
            writeInt(body, 2);

            int baseOffset = body.size();
            writeObject(body, source);
            int deltaOffset = body.size();
            byte[] delta = replaceFromSixBytePrefixDelta(source, target);
            writeDeltaHeader(body, 6, delta.length);
            writeOffsetDeltaBaseDistance(body, deltaOffset - baseOffset);
            writeDeflated(body, delta);

            return withChecksum(body.toByteArray());
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static byte[] packWithReferenceDelta(
            String baseId,
            byte[] source,
            byte[] target) {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeInt(body, 0x5041434b);
            writeInt(body, 2);
            writeInt(body, 1);

            byte[] delta = replaceFromSixBytePrefixDelta(source, target);
            writeDeltaHeader(body, 7, delta.length);
            body.writeBytes(HexFormat.of().parseHex(baseId));
            writeDeflated(body, delta);

            return withChecksum(body.toByteArray());
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void writeObject(
            ByteArrayOutputStream output,
            byte[] data) throws IOException {
        int size = data.length;
        int first = (ObjectType.BLOB.packTypeId() << 4)
                | (size & 0x0f);
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

    private static void writeDeltaHeader(
            ByteArrayOutputStream output,
            int typeId,
            int size) {
        int first = (typeId << 4) | (size & 0x0f);
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
    }

    private static void writeOffsetDeltaBaseDistance(
            ByteArrayOutputStream output,
            int distance) {
        if (distance < 1 || distance > 127) {
            throw new IllegalArgumentException(
                    "test helper supports one-byte offset delta distances");
        }
        output.write(distance);
    }

    private static byte[] replaceFromSixBytePrefixDelta(
            byte[] source,
            byte[] target) {
        if (source.length < 6 || target.length < 6) {
            throw new IllegalArgumentException(
                    "test delta expects a six-byte shared prefix");
        }
        ByteArrayOutputStream delta = new ByteArrayOutputStream();
        writeDeltaVarInt(delta, source.length);
        writeDeltaVarInt(delta, target.length);
        delta.write(0x90);
        delta.write(6);
        int insertLength = target.length - 6;
        delta.write(insertLength);
        delta.write(target, 6, insertLength);
        return delta.toByteArray();
    }

    private static void writeDeltaVarInt(
            ByteArrayOutputStream output,
            int value) {
        do {
            int next = value & 0x7f;
            value >>>= 7;
            if (value != 0) {
                next |= 0x80;
            }
            output.write(next);
        } while (value != 0);
    }

    private static void writeDeflated(
            ByteArrayOutputStream output,
            byte[] data) throws IOException {
        try (DeflaterOutputStream deflater =
                     new DeflaterOutputStream(output)) {
            deflater.write(data);
        }
    }

    private static byte[] withChecksum(byte[] bodyBytes) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.writeBytes(bodyBytes);
        result.writeBytes(sha1(bodyBytes));
        return result.toByteArray();
    }

    private static void assertPackIndex(
            byte[] index,
            byte[] pack,
            List<String> expectedObjectIds) {
        int objectCount = expectedObjectIds.size();
        int namesOffset = 8 + 256 * Integer.BYTES;
        int crcOffset = namesOffset + objectCount * 20;
        int offsetTableOffset = crcOffset + objectCount * Integer.BYTES;
        int checksumOffset = offsetTableOffset + objectCount * Integer.BYTES;
        assertThat(index.length).isEqualTo(checksumOffset + 40);
        assertThat(intAt(index, 0)).isEqualTo(0xff744f63);
        assertThat(intAt(index, 4)).isEqualTo(2);
        assertThat(intAt(index, 8 + 255 * Integer.BYTES))
                .isEqualTo(objectCount);
        List<String> actualObjectIds = objectIdsAt(
                index,
                namesOffset,
                objectCount);
        assertThat(actualObjectIds).containsExactlyElementsOf(
                expectedObjectIds);
        int[] offsets = objectOffsetsAt(
                index,
                offsetTableOffset,
                objectCount,
                pack.length);
        assertCrcs(index, crcOffset, pack, offsets);
        assertThat(Arrays.copyOfRange(index, checksumOffset, checksumOffset + 20))
                .isEqualTo(packChecksumBytes(pack));
        assertThat(Arrays.copyOfRange(index, index.length - 20, index.length))
                .isEqualTo(sha1(Arrays.copyOf(index, index.length - 20)));
    }

    private static List<String> objectIdsAt(
            byte[] index,
            int offset,
            int objectCount) {
        HexFormat hex = HexFormat.of();
        List<String> objectIds = new ArrayList<>(objectCount);
        for (int objectIndex = 0; objectIndex < objectCount; objectIndex++) {
            int objectOffset = offset + objectIndex * 20;
            objectIds.add(hex.formatHex(
                    Arrays.copyOfRange(index, objectOffset, objectOffset + 20)));
        }
        return objectIds;
    }

    private static int[] objectOffsetsAt(
            byte[] index,
            int offset,
            int objectCount,
            int packBytes) {
        int[] objectOffsets = new int[objectCount];
        for (int objectIndex = 0; objectIndex < objectCount; objectIndex++) {
            int tableValue = intAt(
                    index,
                    offset + objectIndex * Integer.BYTES);
            assertThat(tableValue & 0x80000000).isZero();
            assertThat(tableValue).isGreaterThanOrEqualTo(12);
            assertThat(tableValue).isLessThan(packBytes - 20);
            objectOffsets[objectIndex] = tableValue;
        }
        return objectOffsets;
    }

    private static void assertCrcs(
            byte[] index,
            int crcOffset,
            byte[] pack,
            int[] offsets) {
        for (int objectIndex = 0; objectIndex < offsets.length; objectIndex++) {
            int objectOffset = offsets[objectIndex];
            int objectEnd = objectEnd(pack, offsets, objectOffset);
            assertThat(intAt(index, crcOffset + objectIndex * Integer.BYTES))
                    .isEqualTo(crc32(pack, objectOffset, objectEnd - objectOffset));
        }
    }

    private static int objectEnd(
            byte[] pack,
            int[] offsets,
            int objectOffset) {
        int end = pack.length - 20;
        for (int candidate : offsets) {
            if (candidate > objectOffset && candidate < end) {
                end = candidate;
            }
        }
        return end;
    }

    private static int crc32(
            byte[] bytes,
            int offset,
            int length) {
        CRC32 crc = new CRC32();
        crc.update(bytes, offset, length);
        return (int) crc.getValue();
    }

    private static Path singlePathWithSuffix(
            Path rootDirectory,
            String suffix) throws IOException {
        List<Path> matches = pathsWithSuffix(rootDirectory, suffix);
        assertThat(matches).hasSize(1);
        return matches.get(0);
    }

    private static List<Path> pathsWithSuffix(
            Path rootDirectory,
            String suffix) throws IOException {
        try (var paths = Files.walk(rootDirectory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .toList();
        }
    }

    private static List<String> sortedIds(String... ids) {
        List<String> sorted = new ArrayList<>(Arrays.asList(ids));
        sorted.sort(String::compareTo);
        return sorted;
    }

    private static String packChecksum(byte[] pack) {
        return HexFormat.of().formatHex(packChecksumBytes(pack));
    }

    private static byte[] packChecksumBytes(byte[] pack) {
        return Arrays.copyOfRange(pack, pack.length - 20, pack.length);
    }

    private static String blobId(byte[] data) {
        byte[] header = ("blob " + data.length + "\0")
                .getBytes(StandardCharsets.UTF_8);
        MessageDigest digest = sha1Digest();
        digest.update(header);
        return HexFormat.of().formatHex(digest.digest(data));
    }

    private static byte[] sha1(byte[] bytes) {
        return sha1Digest().digest(bytes);
    }

    private static MessageDigest sha1Digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static int intAt(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 24)
                | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xff);
    }

    private static void writeInt(
            ByteArrayOutputStream output,
            int value) {
        output.write((value >>> 24) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }
}
