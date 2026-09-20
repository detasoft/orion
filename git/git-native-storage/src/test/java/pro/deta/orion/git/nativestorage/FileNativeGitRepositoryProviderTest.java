package pro.deta.orion.git.nativestorage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.read.ResolvedGitObjectRead;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.util.Result;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileNativeGitRepositoryProviderTest {
    private static final String NULL_ID = "0".repeat(40);

    @Test
    void reopensPersistedRefsAndObjects(@TempDir Path rootDirectory) {
        FileNativeGitRepositoryProvider first =
                new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository repository = first.create(
                "team/project").valueOrFailure("repository");
        ObjectId blob = repository.writeObject(
                GitObjectType.BLOB,
                "persistent".getBytes(StandardCharsets.UTF_8));
        repository.updateRef("refs/heads/main", NULL_ID, blob.toHex());

        FileNativeGitRepositoryProvider second =
                new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository reopened = second.find("team/project")
                .valueOrFailure("repository");

        assertThat(reopened.name()).isEqualTo("team/project");
        assertThat(reopened.defaultHead()).isEqualTo("refs/heads/main");
        assertThat(reopened.refs())
                .containsEntry("refs/heads/main", blob.toHex());
        assertThat(reopened.readObject(blob))
                .isPresent()
                .get()
                .satisfies(object -> {
                    assertThat(object.type()).isEqualTo(GitObjectType.BLOB);
                    assertThat(object.data()).isEqualTo(
                            "persistent".getBytes(StandardCharsets.UTF_8));
                });
    }

    @Test
    void repositoryNamesDoNotMapDirectlyToFileSystemPaths(
            @TempDir Path rootDirectory) {
        FileNativeGitRepositoryProvider provider =
                new FileNativeGitRepositoryProvider(rootDirectory);

        provider.create("team/project")
                .valueOrFailure("repository");

        assertThat(Files.exists(rootDirectory.resolve("team"))).isFalse();
    }

    @Test
    void listsPersistedRepositoriesInStableOrder(@TempDir Path rootDirectory) {
        FileNativeGitRepositoryProvider first =
                new FileNativeGitRepositoryProvider(rootDirectory);
        first.create("team/zeta").valueOrFailure("repository");
        first.create("alpha").valueOrFailure("repository");

        FileNativeGitRepositoryProvider reopened =
                new FileNativeGitRepositoryProvider(rootDirectory);

        assertThat(reopened.repositoryNames()).containsExactly("alpha", "team/zeta");
    }

    @Test
    void createFailsWhenRepositoryAlreadyExists(@TempDir Path rootDirectory) {
        FileNativeGitRepositoryProvider provider =
                new FileNativeGitRepositoryProvider(rootDirectory);
        provider.create("project").valueOrFailure("repository");

        Result<NativeGitRepository> result = provider.create("project");

        assertThat(result).isInstanceOf(Result.Failure.class);
        assertThat(((Result.Failure<?>) result).code())
                .isEqualTo(Result.FailureCode.FILE_ALREADY_EXISTS);
    }

    @Test
    void findDoesNotCreateRepository(@TempDir Path rootDirectory) {
        FileNativeGitRepositoryProvider provider =
                new FileNativeGitRepositoryProvider(rootDirectory);

        Result<NativeGitRepository> result = provider.find("missing");

        assertThat(result).isInstanceOf(Result.Failure.class);
        assertThat(provider.exists("missing")).isFalse();
    }

    @Test
    void canonicalizesNamesBeforeLookupAndPersistsTheCanonicalIdentity(
            @TempDir Path rootDirectory) {
        FileNativeGitRepositoryProvider first =
                new FileNativeGitRepositoryProvider(rootDirectory);

        NativeGitRepository created = first.create("team%2Frepo")
                .valueOrFailure("repository");

        assertThat(created.name()).isEqualTo("team/repo");
        assertThat(first.find("team/repo").valueOrFailure("repository"))
                .isSameAs(created);
        assertThat(first.create("team/repo")).isInstanceOf(Result.Failure.class);

        FileNativeGitRepositoryProvider reopened =
                new FileNativeGitRepositoryProvider(rootDirectory);
        assertThat(reopened.find("team/repo").valueOrFailure("repository").name())
                .isEqualTo("team/repo");
        assertThat(reopened.repositoryNames()).containsExactly("team/repo");
    }

    @Test
    void rejectsInvalidPersistedRepositoryNames(@TempDir Path rootDirectory) throws IOException {
        FileNativeGitRepositoryProvider provider =
                new FileNativeGitRepositoryProvider(rootDirectory);
        provider.create("team/repo").valueOrFailure("repository");
        Path metadata = singlePathWithSuffix(
                rootDirectory,
                "orion-native-repository.properties");
        String content = Files.readString(metadata, StandardCharsets.UTF_8);
        Files.writeString(
                metadata,
                content.replace("name=team/repo", "name=Team/repo"),
                StandardCharsets.UTF_8);

        FileNativeGitRepositoryProvider reopened =
                new FileNativeGitRepositoryProvider(rootDirectory);

        assertThatThrownBy(reopened::repositoryNames)
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reopened.find("team/repo"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reopensReceivedPackAndItsIndexWithoutChangingBytes(@TempDir Path root) throws Exception {
        NativeGitRepository repository = new FileNativeGitRepositoryProvider(root)
                .create("packed").valueOrFailure("repository");
        byte[] first = "published-one".getBytes(StandardCharsets.UTF_8);
        byte[] second = "published-two".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = pack(first, second);
        PackId id = persist(repository, bytes);
        assertThat(id.toHex()).isEqualTo(packChecksum(bytes));
        repository.close();
        try (NativeGitRepository reopened = new FileNativeGitRepositoryProvider(root)
                .find("packed").valueOrFailure("repository")) {
            try (IndexedPack stored = reopened.storage().openPack(id).orElseThrow();
                 BufferedByteInputV2 input = stored.input()) {
                assertThat(input.newInputStream().readAllBytes()).isEqualTo(bytes);
                assertThat(stored.find(new ObjectId(blobId(first)))).isPresent();
                assertThat(stored.find(new ObjectId(blobId(second)))).isPresent();
            }
            assertPublishedObject(reopened, blobId(first), first);
            assertPublishedObject(reopened, blobId(second), second);
            assertThat(reopened.readObject(new ObjectId("f".repeat(40)))).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readsOffsetAndCompletedReferenceDeltasAfterReopen(boolean reference, @TempDir Path root) throws Exception {
        NativeGitRepository repository = new FileNativeGitRepositoryProvider(root)
                .create("packed").valueOrFailure("repository");
        byte[] base = "hello world".getBytes(StandardCharsets.UTF_8);
        byte[] target = "hello native".getBytes(StandardCharsets.UTF_8);
        if (reference) {
            persist(repository, pack(base));
        }
        PackId id = persist(repository, reference
                ? packWithReferenceDelta(blobId(base), base, target) : packWithOffsetDelta(base, target));
        repository.close();
        try (NativeGitRepository reopened = new FileNativeGitRepositoryProvider(root)
                .find("packed").valueOrFailure("repository")) {
            try (IndexedPack stored = reopened.storage().openPack(id).orElseThrow()) {
                assertThat(stored.find(new ObjectId(blobId(base)))).isPresent();
                assertThat(stored.find(new ObjectId(blobId(target)))).isPresent();
            }
            assertPublishedObject(reopened, blobId(target), target);
            assertThat(reopened.storage().readObject(new ObjectId(blobId(target)),
                    new ResolvedGitObjectRead<>(reopened.storage(), (type, size, baseId, input) -> {
                        assertThat(type).isEqualTo(GitObjectType.BLOB);
                        assertThat(size).isEqualTo(target.length);
                        return input.readBytes(7);
                    }))).hasValueSatisfying(prefix ->
                            assertThat(prefix).isEqualTo("hello n".getBytes(StandardCharsets.UTF_8)));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsThinPackWithMissingOrCorruptBase(boolean corrupt, @TempDir Path root) throws Exception {
        try (NativeGitRepository repository = new FileNativeGitRepositoryProvider(root)
                .create("packed").valueOrFailure("repository")) {
            byte[] base = "hello world".getBytes(StandardCharsets.UTF_8);
            byte[] target = "hello native".getBytes(StandardCharsets.UTF_8);
            if (corrupt) {
                persist(repository, pack(base));
                Path path = singlePathWithSuffix(root, ".pack");
                byte[] bytes = Files.readAllBytes(path);
                bytes[bytes.length - 1] ^= 1;
                Files.write(path, bytes);
            }
            List<PackId> before = repository.storage().packIds();
            assertThatThrownBy(() -> persist(repository, packWithReferenceDelta(blobId(base), base, target)))
                    .isInstanceOf(IOException.class);
            assertThat(repository.storage().packIds()).containsExactlyElementsOf(before);
            assertThat(repository.refs()).isEmpty();
        }
    }

    @Test
    void malformedPackLeavesNoPublishedObjectsOrRefs(@TempDir Path root) throws Exception {
        try (NativeGitRepository repository = new FileNativeGitRepositoryProvider(root)
                .create("packed").valueOrFailure("repository")) {
            byte[] bytes = pack("broken".getBytes(StandardCharsets.UTF_8));
            bytes[bytes.length - 1] ^= 1;
            assertThatThrownBy(() -> persist(repository, bytes)).isInstanceOf(IOException.class);
            assertThat(repository.storage().packIds()).isEmpty();
            assertThat(repository.refs()).isEmpty();
            assertThat(pathsWithSuffix(root, ".pack")).isEmpty();
        }
    }

    private static PackId persist(NativeGitRepository repository, byte[] bytes) throws IOException {
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(bytes))) {
            IndexedPack pack = repository.ingest(input);
            try {
                return repository.storage().persist(pack);
            } finally {
                pack.discard();
            }
        }
    }

    private static void assertPublishedObject(NativeGitRepository repository, String id, byte[] expected) {
        assertThat(repository.readObject(new ObjectId(id))).hasValueSatisfying(object -> {
            assertThat(object.type()).isEqualTo(GitObjectType.BLOB);
            assertThat(object.data()).isEqualTo(expected);
        });
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
        int first = (GitObjectType.BLOB.code() << 4)
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
        try (java.util.stream.Stream<Path> paths = Files.walk(rootDirectory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .toList();
        }
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
        return GitHashAlgorithm.SHA1.newDigest();
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
