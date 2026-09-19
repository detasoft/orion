package pro.deta.orion.git.parser.v2.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.pack.GitPackObjectResolver;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.pack.PackTestData;
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
                PackId received = attempt.pack.id();
                assertThat(new GitPackObjectResolver(attempt.pack, attempt.storage).complete())
                        .isEqualTo(received);
                assertThat(Files.readAllBytes(attempt.packPath)).containsExactly(pack);
                assertThat(Files.exists(attempt.temporaryPath)).isFalse();
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
            PackTestData.store(attempt.storage, GitObjectType.BLOB, base);
            resolve(attempt, result, otherResult);

            PackId received = attempt.pack.id();
            PackId completed = new GitPackObjectResolver(attempt.pack, attempt.storage).complete();
            byte[] output = Files.readAllBytes(attempt.packPath);
            assertThat(completed).isNotEqualTo(received);
            assertThat(completed).isEqualTo(checksum(output));
            assertThat(ByteBuffer.wrap(output).getInt(8)).isEqualTo(3);
            assertThat(Arrays.copyOfRange(output, 12, original.length - 20))
                    .containsExactly(Arrays.copyOfRange(original, 12, original.length - 20));
            var appended = attempt.pack.find(baseId).orElseThrow();
            assertThat(appended.offset()).isEqualTo(original.length - 20);
            assertThat(appended.type()).isEqualTo(GitObjectType.BLOB);
            attempt.pack.close();
            try (var index = IndexedPack.open(attempt.packPath, attempt.indexPath)) {
                assertThat(index.find(baseId)).contains(appended);
            }
            assertGitIndexes(attempt.packPath);
        }
    }

    @Test
    void rejectsRepeatedObjectsThatGitCannotIndex() throws Exception {
        byte[] object = full(new byte[]{1, 2, 3});
        try (var attempt = new Attempt(pack(object, object))) {
            assertThatThrownBy(() -> new GitPackObjectResolver(attempt.pack, attempt.storage).complete())
                    .isInstanceOf(IOException.class).hasMessageContaining("duplicate");
        }
    }

    @Test
    void restoresAPublishedDeltaBaseAndAppendsItsFullContent() throws Exception {
        byte[] root = {1, 2, 3};
        byte[] base = {1, 2, 4};
        ObjectId rootId = objectId(root);
        ObjectId baseId = objectId(base);
        try (var attempt = new Attempt(pack(delta(baseId, new byte[]{3, 1, 1, 9})))) {
            PackTestData.storeDelta(attempt.storage, GitObjectType.BLOB, root,
                    new byte[]{3, 3, 3, 1, 2, 4}, base);
            resolve(attempt, new byte[]{9});

            new GitPackObjectResolver(attempt.pack, attempt.storage).complete();
            assertThat(attempt.pack.find(baseId).orElseThrow().type()).isEqualTo(GitObjectType.BLOB);
            assertThat(attempt.pack.find(rootId)).isEmpty();
            attempt.pack.close();
            assertGitIndexes(attempt.packPath);
        }
    }

    @Test
    void missingBaseFailsAndClosesTheUnpublishableAttempt() throws Exception {
        ObjectId base = objectId(new byte[]{1, 2, 3});
        try (Attempt attempt = new Attempt(pack(delta(base, new byte[]{3, 1, 1, 9})))) {
            resolve(attempt, new byte[]{9});
            assertThatThrownBy(() -> new GitPackObjectResolver(attempt.pack, attempt.storage).complete())
                    .isInstanceOf(IOException.class).hasMessageContaining("Missing external base");
            assertThatThrownBy(attempt.pack::size).isInstanceOf(ClosedChannelException.class);
        }
    }

    @Test
    void rejectsUnresolvedEntriesAndWrongReceivedChecksums() throws Exception {
        try (var attempt = new Attempt(pack(delta(objectId(new byte[]{1}), new byte[]{1, 1, 1, 2})))) {
            assertThatThrownBy(() -> new GitPackObjectResolver(attempt.pack, attempt.storage).complete())
                    .isInstanceOf(IOException.class).hasMessageContaining("unresolved");
        }
        try (var attempt = new Attempt(pack())) {
            attempt.pack.write(attempt.pack.size() - 1, ByteBuffer.wrap(new byte[]{42}));
            assertThatThrownBy(() -> new GitPackObjectResolver(attempt.pack, attempt.storage).complete())
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

    private static void resolve(Attempt attempt, byte[]... contents) throws Exception {
        assertThat(attempt.pack.entryCount()).isEqualTo(contents.length);
        long offset = 12;
        for (byte[] content : contents) {
            attempt.pack.addObject(offset, objectId(content), GitObjectType.BLOB, content.length);
            offset = attempt.pack.dataEnd(offset);
        }
    }

    private static ObjectId objectId(byte[] content) throws Exception {
        try (var input = new InputStreamBufferedByteInput(new ByteArrayInputStream(compressed(content)))) {
            return new HashedGitObjectRead().read(GitObjectType.BLOB, content.length, Optional.empty(), input);
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
        private final GitStorageApi storage;
        private final IndexedPack pack;

        private Attempt(byte[] bytes) throws IOException {
            Path parent = Files.createTempDirectory(directory, "attempt-");
            storage = new GitStorageApi(parent);
            Path attempt = parent.resolve("pack");
            packPath = attempt.resolve("data.pack");
            indexPath = attempt.resolve("data.mv");
            temporaryPath = attempt.resolve("data.tmv");
            pack = PackTestData.ingest(bytes, IndexedPack.create(attempt));
        }

        @Override
        public void close() throws IOException {
            pack.close();
        }
    }
}
