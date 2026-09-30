package pro.deta.orion.git.parser.v2.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.index.GitIndexApi;
import pro.deta.orion.git.parser.v2.index.IndexedObject;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.git.parser.v2.index.local.LocalGitIndex;
import pro.deta.orion.git.parser.v2.index.memory.InMemoryIndex;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.git.parser.v2.read.ResolvedGitObjectRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.storage.local.LocalGitStorage;
import pro.deta.orion.git.parser.v2.storage.memory.InMemoryStorage;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static pro.deta.orion.git.parser.v2.pack.PackTestData.*;

class GitPackObjectResolverTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"false, 2", "true, 2", "false, 3", "true, 3"})
    void resolvesForwardReferencesBranchesAndOffsetDeltasWithoutChangingCompressedBytes(boolean memory, int version)
            throws Exception {
        try (GitStorageApi storage = memory ? new InMemoryStorage() : new LocalGitStorage(directory);
             GitIndexApi index = memory ? new InMemoryIndex() : new LocalGitIndex(directory)) {
            ObjectId first = objectId(GitObjectType.BLOB, new byte[]{1});
            ObjectId second = objectId(GitObjectType.BLOB, new byte[]{2});
            byte[] forward = delta(second, new byte[]{1, 1, 1, 3});
            byte[] offset = join(PackEntryWriter.objectHeader(GitObjectType.OFS_DELTA, 4),
                    new byte[]{(byte) forward.length}, compressed(new byte[]{1, 1, 1, 4}));
            byte[] source = pack(version, forward, offset, delta(second, new byte[]{1, 1, 1, 5}),
                    delta(first, new byte[]{1, 1, 1, 2}), blob(new byte[]{1}));
            PackMetadata completed = publish(source, storage, index);
            assertThat(completed.objectCount()).isEqualTo(5);
            IndexedObject stored = index.objects(completed.packId()).getFirst();
            assertThat(GitObjectRead.<byte[]>read(storage, stored,
                    (type, size, base, input) -> input.newInputStream().readAllBytes()))
                    .containsExactly(compressed(new byte[]{1, 1, 1, 3}));
            for (byte value = 1; value <= 5; value++) {
                byte expected = value;
                assertThat(GitObjectRead.read(storage, index, objectId(GitObjectType.BLOB, new byte[]{value}),
                        new ResolvedGitObjectRead<>(storage, index,
                                (type, size, base, input) -> input.readBytes((int) size))))
                        .hasValueSatisfying(content -> assertThat(content).containsExactly(expected));
            }
            try (GitStorageApi replay = new InMemoryStorage(); GitIndexApi replayIndex = new InMemoryIndex()) {
                PackMetadata copy = publish(bytes(completed, storage, index), replay, replayIndex);
                assertThat(copy.packChecksum()).isEqualTo(completed.packChecksum());
                assertThat(copy.objectCount()).isEqualTo(5);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void completesThinPackWithExternalBaseAndPersistsIt(int version) throws Exception {
        try (GitStorageApi storage = new LocalGitStorage(directory); GitIndexApi index = new LocalGitIndex(directory)) {
            ObjectId base = store(storage, index, GitObjectType.BLOB, new byte[]{1});
            byte[] source = pack(version, delta(base, new byte[]{1, 1, 1, 2}));
            PackMetadata completed = publish(source, storage, index);
            assertThat(completed.objectCount()).isEqualTo(2);
            try (GitStorageApi copy = new InMemoryStorage(); GitIndexApi copyIndex = new InMemoryIndex()) {
                publish(bytes(completed, storage, index), copy, copyIndex);
                assertThat(GitObjectRead.read(copy, copyIndex, objectId(GitObjectType.BLOB, new byte[]{2}),
                        new ResolvedGitObjectRead<>(copy, copyIndex,
                                (type, size, unused, input) -> input.readBytes((int) size))))
                        .hasValueSatisfying(content -> assertThat(content).containsExactly((byte) 2));
            }
        }
    }

    @Test
    void rejectsSelfReferencingDeltaEvenWhenItsContentCanBeResolvedExternally() throws Exception {
        try (GitStorageApi storage = new InMemoryStorage(); GitIndexApi index = new InMemoryIndex()) {
            ObjectId base = store(storage, index, GitObjectType.BLOB, new byte[]{1});
            List<PackMetadata> before = index.packs();
            assertThatThrownBy(() -> ingest(pack(delta(base, new byte[]{1, 1, (byte) 0x90, 1})),
                    storage, index)).isInstanceOf(IOException.class).hasMessageContaining("cycle");
            assertThat(index.packs()).isEqualTo(before);
        }
    }

    @Test
    void doesNotAppendAPublishedBaseThatLaterResolvesInsideThePack() throws Exception {
        try (GitStorageApi storage = new InMemoryStorage(); GitIndexApi index = new InMemoryIndex()) {
            ObjectId base = store(storage, index, GitObjectType.BLOB, new byte[]{2});
            ObjectId root = objectId(GitObjectType.BLOB, new byte[]{1});
            byte[] source = pack(delta(base, new byte[]{1, 1, 1, 3}),
                    delta(root, new byte[]{1, 1, 1, 2}), blob(new byte[]{1}));
            PackMetadata completed = publish(source, storage, index);
            assertThat(completed.objectCount()).isEqualTo(3);
            assertThat(bytes(completed, storage, index)).containsExactly(source);
        }
    }

    @Test
    void resolvesADeepForwardChainWithoutRecursiveCalls() throws Exception {
        try (GitStorageApi storage = new InMemoryStorage(); GitIndexApi index = new InMemoryIndex()) {
            List<byte[]> entries = new ArrayList<>();
            for (int value = 1100; value > 0; value--) {
                byte[] previous = {(byte) ((value - 1) >>> 8), (byte) (value - 1)};
                entries.add(delta(objectId(GitObjectType.BLOB, previous),
                        new byte[]{2, 2, 2, (byte) (value >>> 8), (byte) value}));
            }
            entries.add(blob(new byte[]{0, 0}));
            byte[] source = pack(entries.toArray(byte[][]::new));
            PackMetadata completed = assertTimeout(Duration.ofSeconds(30), () -> publish(source, storage, index));
            assertThat(completed.objectCount()).isEqualTo(1101);
            assertThat(bytes(completed, storage, index)).containsExactly(source);
        }
    }

    @Test
    void rejectsInvalidDeltaInstructionsBeforePublication() throws Exception {
        try (GitStorageApi storage = new InMemoryStorage(); GitIndexApi index = new InMemoryIndex()) {
            byte[][] invalid = {{2, 0}, {1, 1, 0}, {1, 1, 1}, {1, 1, (byte) 0x91, 1, 1},
                    {1, 1, (byte) 0x90, 2}, {1, 1}, {1, 0, 1, 42}, {1, 1, (byte) 0x91}};
            for (byte[] instructions : invalid) {
                byte[] source = pack(blob(new byte[]{1}),
                        delta(objectId(GitObjectType.BLOB, new byte[]{1}), instructions));
                assertThatThrownBy(() -> publish(source, storage, index)).isInstanceOf(IOException.class);
                assertThat(index.packs()).isEmpty();
            }
        }
    }

    @Test
    void rejectsMissingBasesCyclesAndDuplicateObjects() throws Exception {
        try (GitStorageApi storage = new InMemoryStorage(); GitIndexApi index = new InMemoryIndex()) {
            ObjectId absent = objectId(GitObjectType.BLOB, new byte[]{1});
            ObjectId other = objectId(GitObjectType.BLOB, new byte[]{2});
            for (byte[] source : new byte[][] {pack(delta(absent, new byte[]{1, 1, 1, 3})),
                    pack(delta(absent, new byte[]{1, 1, 1, 2}), delta(other, new byte[]{1, 1, 1, 1})),
                    pack(blob(new byte[]{1}), blob(new byte[]{1}))}) {
                assertThatThrownBy(() -> publish(source, storage, index)).isInstanceOf(IOException.class);
                assertThat(index.packs()).isEmpty();
            }
        }
    }
}
