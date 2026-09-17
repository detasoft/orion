package pro.deta.orion.git.parser.v2.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.pack.PackUpload;
import pro.deta.orion.git.parser.v2.read.HashedGitObjectRead;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackCompletionTest {
    @TempDir
    Path directory;

    @Test
    void leavesSelfContainedAndEmptyPacksUnchanged() throws Exception {
        for (byte[] pack : new byte[][]{pack(), pack(full(new byte[]{1, 2, 3}))}) {
            try (var attempt = new Attempt(pack)) {
                while (attempt.upload.hasNext()) {
                    attempt.upload.next();
                }
                PackId received = attempt.upload.packId();
                assertThat(GitPackStorage.complete(attempt.bytes, attempt.index, attempt.storage.api, received))
                        .isEqualTo(received);
                assertThat(Files.readAllBytes(attempt.packPath)).containsExactly(pack);
                assertThat(Files.exists(attempt.temporaryPath)).isFalse();
                assertThat(attempt.storage.lookups).isEmpty();
            }
        }
    }

    @Test
    void appendsOneSharedBaseChangesPackIdAndProducesAPackGitCanIndexWithoutExternalObjects() throws Exception {
        byte[] base = new byte[30_000];
        new java.util.Random(17).nextBytes(base);
        byte[] result = {1, 2, 4};
        byte[] otherResult = {1, 2, 5};
        ObjectId baseId = objectId(base);
        byte[] delta = delta(baseId, join(size(base.length), new byte[]{3, 3, 1, 2, 4}));
        byte[] otherDelta = delta(baseId, join(size(base.length), new byte[]{3, 3, 1, 2, 5}));
        byte[] original = pack(delta, otherDelta);
        try (var attempt = new Attempt(original)) {
            attempt.storage.put(baseId, ObjectType.BLOB, Optional.empty(), base);
            resolve(attempt, result);
            resolve(attempt, otherResult);
            assertThat(attempt.upload.hasNext()).isFalse();
            PackId received = attempt.upload.packId();
            PackId completed = GitPackStorage.complete(attempt.bytes, attempt.index, attempt.storage.api, received);
            byte[] output = Files.readAllBytes(attempt.packPath);
            assertThat(completed).isNotEqualTo(received);
            assertThat(completed).isEqualTo(checksum(output));
            assertThat(ByteBuffer.wrap(output).getInt(8)).isEqualTo(3);
            assertThat(Arrays.copyOfRange(output, 12, original.length - 20))
                    .containsExactly(Arrays.copyOfRange(original, 12, original.length - 20));
            assertThat(attempt.storage.lookups).containsExactly(baseId);
            var appended = attempt.index.find(baseId).orElseThrow();
            assertThat(appended.offset()).isEqualTo(original.length - 20);
            assertThat(appended.type()).isEqualTo(ObjectType.BLOB);
            attempt.index.close();
            attempt.bytes.close();
            try (var index = StoredPackIndex.open(attempt.indexPath)) {
                assertThat(index.find(baseId)).contains(appended);
            }
            assertGitIndexes(attempt.packPath);
        }
    }

    @Test
    void rejectsRepeatedObjectsThatGitCannotIndex() throws Exception {
        byte[] object = full(new byte[]{1, 2, 3});
        try (var attempt = new Attempt(pack(object, object))) {
            while (attempt.upload.hasNext()) {
                attempt.upload.next();
            }
            PackId received = attempt.upload.packId();
            assertThatThrownBy(() -> GitPackStorage.complete(attempt.bytes, attempt.index,
                    attempt.storage.api, received)).isInstanceOf(IOException.class).hasMessageContaining("duplicate");
        }
    }

    @Test
    void restoresAPublishedDeltaBaseAndAppendsItsFullContent() throws Exception {
        byte[] root = {1, 2, 3};
        byte[] base = {1, 2, 4};
        ObjectId rootId = objectId(root);
        ObjectId baseId = objectId(base);
        try (var attempt = new Attempt(pack(delta(baseId, new byte[]{3, 1, 1, 9})))) {
            attempt.storage.put(rootId, ObjectType.BLOB, Optional.empty(), root);
            attempt.storage.put(baseId, ObjectType.REF_DELTA, Optional.of(rootId),
                    new byte[]{3, 3, 3, 1, 2, 4});
            resolve(attempt, new byte[]{9});
            assertThat(attempt.upload.hasNext()).isFalse();
            GitPackStorage.complete(attempt.bytes, attempt.index, attempt.storage.api, attempt.upload.packId());
            assertThat(attempt.storage.lookups).containsExactly(baseId, rootId);
            assertThat(attempt.index.find(baseId).orElseThrow().type()).isEqualTo(ObjectType.BLOB);
            assertThat(attempt.index.find(rootId)).isEmpty();
            attempt.bytes.close();
            assertGitIndexes(attempt.packPath);
        }
    }

    @Test
    void missingOrIncorrectBaseFailsAndClosesTheUnpublishableAttempt() throws Exception {
        ObjectId base = objectId(new byte[]{1, 2, 3});
        for (boolean wrongContent : new boolean[]{false, true}) {
            try (var attempt = new Attempt(pack(delta(base, new byte[]{3, 1, 1, 9})))) {
                if (wrongContent) {
                    attempt.storage.put(base, ObjectType.BLOB, Optional.empty(), new byte[]{4, 5, 6});
                }
                resolve(attempt, new byte[]{9});
                assertThat(attempt.upload.hasNext()).isFalse();
                PackId received = attempt.upload.packId();
                assertThatThrownBy(() -> GitPackStorage.complete(attempt.bytes, attempt.index,
                        attempt.storage.api, received)).isInstanceOf(IOException.class);
                assertThat(attempt.bytes.isOpen()).isFalse();
                assertThatThrownBy(attempt.index::hasUnresolved).isInstanceOf(ClosedChannelException.class);
            }
        }
    }

    @Test
    void rejectsUnresolvedEntriesAndWrongReceivedChecksums() throws Exception {
        try (var attempt = new Attempt(pack(delta(objectId(new byte[]{1}), new byte[]{1, 1, 1, 2})))) {
            attempt.upload.next();
            assertThat(attempt.upload.hasNext()).isFalse();
            PackId received = attempt.upload.packId();
            assertThatThrownBy(() -> GitPackStorage.complete(attempt.bytes, attempt.index,
                    attempt.storage.api, received)).isInstanceOf(IOException.class).hasMessageContaining("unresolved");
        }
        try (var attempt = new Attempt(pack())) {
            assertThat(attempt.upload.hasNext()).isFalse();
            assertThatThrownBy(() -> GitPackStorage.complete(attempt.bytes, attempt.index,
                    attempt.storage.api, new PackId(new byte[20])))
                    .isInstanceOf(IOException.class).hasMessageContaining("checksum");
        }
    }

    private void assertGitIndexes(Path pack) throws Exception {
        Path repository = Files.createTempDirectory(directory, "git-");
        runGit(repository, "init", "--bare", repository.toString());
        runGit(repository, "index-pack", pack.toString());
        Path index = pack.resolveSibling(pack.getFileName().toString().replace(".pack", ".idx"));
        assertThat(Files.size(index)).isPositive();
        runGit(repository, "verify-pack", index.toString());
    }

    private static void runGit(Path directory, String... args) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.addAll(java.util.List.of(args));
        var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().keySet().removeIf(name -> name.startsWith("GIT_"));
        Process process = builder.start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isZero();
        } finally {
            process.destroyForcibly();
        }
    }

    private static void resolve(Attempt attempt, byte[] content) throws Exception {
        var entry = attempt.upload.next().entry();
        attempt.index.addObject(entry, objectId(content), ObjectType.BLOB, content.length);
    }

    private static ObjectId objectId(byte[] content) throws Exception {
        try (var input = new InputStreamBufferedByteInput(new ByteArrayInputStream(compressed(content)))) {
            return new HashedGitObjectRead().read(ObjectType.BLOB, content.length, Optional.empty(), input);
        }
    }

    private static byte[] full(byte[] content) throws Exception {
        return join(new byte[]{(byte) (0x30 | content.length)}, compressed(content));
    }

    private static byte[] size(int value) {
        var bytes = new ByteArrayOutputStream();
        do {
            int part = value & 127;
            value >>>= 7;
            bytes.write(part | (value == 0 ? 0 : 128));
        } while (value != 0);
        return bytes.toByteArray();
    }

    private static byte[] delta(ObjectId base, byte[] instructions) throws Exception {
        return join(new byte[]{(byte) (0x70 | instructions.length)}, base.toBytes(), compressed(instructions));
    }

    private static byte[] compressed(byte[] content) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zlib = new DeflaterOutputStream(output)) {
            zlib.write(content);
        }
        return output.toByteArray();
    }

    private static byte[] pack(byte[]... entries) throws Exception {
        byte[] body = join(ByteBuffer.allocate(12).putInt(0x5041434b).putInt(2).putInt(entries.length).array(),
                join(entries));
        return join(body, MessageDigest.getInstance("SHA-1").digest(body));
    }

    private static PackId checksum(byte[] pack) throws Exception {
        byte[] expected = MessageDigest.getInstance("SHA-1").digest(Arrays.copyOf(pack, pack.length - 20));
        assertThat(Arrays.copyOfRange(pack, pack.length - 20, pack.length)).containsExactly(expected);
        return new PackId(expected);
    }

    private static byte[] join(byte[]... parts) {
        var output = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            output.writeBytes(part);
        }
        return output.toByteArray();
    }

    private final class Attempt implements AutoCloseable {
        private final Path packPath;
        private final Path indexPath;
        private final Path temporaryPath;
        private final InMemoryGitStorage storage = new InMemoryGitStorage();
        private final InputStreamBufferedByteInput source;
        private final FilePackByteStore bytes;
        private final FilePackIndex index;
        private final PackUpload upload;

        private Attempt(byte[] pack) throws IOException {
            Path attempt = Files.createTempDirectory(directory, "attempt-");
            packPath = attempt.resolve("data.pack");
            indexPath = attempt.resolve("data.mv");
            temporaryPath = attempt.resolve("data.tmv");
            source = new InputStreamBufferedByteInput(new ByteArrayInputStream(pack));
            bytes = new FilePackByteStore(packPath);
            index = FilePackIndex.create(indexPath, temporaryPath);
            upload = new PackUpload(storage.api, source, bytes, index);
        }

        @Override
        public void close() throws IOException {
            try (source; bytes; index) {
                // All handles belong to this fixture; the production owner will also remove staging.
            }
        }
    }
}
