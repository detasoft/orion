package pro.deta.orion.git.parser.v2.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.index.GitIndexApi;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.git.parser.v2.index.local.LocalGitIndex;
import pro.deta.orion.git.parser.v2.storage.local.LocalGitStorage;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.git.parser.v2.pack.PackTestData.*;

class PackCompletionTest {
    @TempDir
    Path directory;

    @Test
    void preservesSelfContainedAndEmptyGitExports() throws Exception {
        try (GitStorageApi storage = new LocalGitStorage(directory); GitIndexApi index = new LocalGitIndex(directory)) {
            for (byte[] bytes : new byte[][]{pack(), pack(blob(new byte[]{1, 2, 3}))}) {
                PackMetadata metadata = ingest(bytes, storage, index);
                byte[] exported = bytes(metadata, storage, index);
                assertThat(exported).containsExactly(bytes);
                Path path = directory.resolve(metadata.packId() + ".pack");
                Files.write(path, exported);
                assertGitIndexes(path);
            }
        }
    }

    @Test
    void appendsSharedExternalBaseOnceAndExportsPackThatGitCanIndex() throws Exception {
        byte[] base = new byte[30_000];
        new Random(17).nextBytes(base);
        try (GitStorageApi storage = new LocalGitStorage(directory); GitIndexApi index = new LocalGitIndex(directory)) {
            ObjectId baseId = store(storage, index, GitObjectType.BLOB, base);
            byte[] original = pack(delta(baseId, new byte[]{(byte) 0xb0, (byte) 0xea, 1, 3, 3, 1, 2, 4}),
                    delta(baseId, new byte[]{(byte) 0xb0, (byte) 0xea, 1, 3, 3, 1, 2, 5}));
            PackMetadata metadata = ingest(original, storage, index);
            byte[] output = bytes(metadata, storage, index);
            assertThat(metadata.objectCount()).isEqualTo(3);
            assertThat(metadata.packChecksum().toBytes())
                    .isNotEqualTo(Arrays.copyOfRange(original, original.length - 20, original.length));
            assertThat(ByteBuffer.wrap(output).getInt(8)).isEqualTo(3);
            assertThat(Arrays.copyOfRange(output, 12, original.length - 20))
                    .containsExactly(Arrays.copyOfRange(original, 12, original.length - 20));
            assertThat(index.findObject(metadata.packId(), baseId).orElseThrow().delta()).isEmpty();
            Path path = directory.resolve("shared.pack");
            Files.write(path, output);
            assertGitIndexes(path);
        }
    }

    @Test
    void restoresPublishedDeltaBaseAndExportsOnlyItsFullContent() throws Exception {
        byte[] root = {1, 2, 3};
        byte[] base = {1, 2, 4};
        try (GitStorageApi storage = new LocalGitStorage(directory); GitIndexApi index = new LocalGitIndex(directory)) {
            ObjectId baseId = storeDelta(storage, index, GitObjectType.BLOB, root,
                    new byte[]{3, 3, 3, 1, 2, 4}, base);
            PackMetadata metadata = ingest(pack(delta(baseId, new byte[]{3, 1, 1, 9})), storage, index);
            assertThat(index.findObject(metadata.packId(), baseId).orElseThrow().delta()).isEmpty();
            assertThat(index.findObject(metadata.packId(), objectId(GitObjectType.BLOB, root))).isEmpty();
            Path path = directory.resolve("restored.pack");
            Files.write(path, bytes(metadata, storage, index));
            assertGitIndexes(path);
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
        var command = new ArrayList<String>();
        command.add("git");
        command.addAll(List.of(args));
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

}
