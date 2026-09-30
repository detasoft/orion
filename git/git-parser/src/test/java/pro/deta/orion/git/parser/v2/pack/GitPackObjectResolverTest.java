package pro.deta.orion.git.parser.v2.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.IndexedObject;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.git.parser.v2.index.local.LocalGitIndex;
import pro.deta.orion.git.parser.v2.index.memory.InMemoryIndex;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.git.parser.v2.read.GitPackRead;
import pro.deta.orion.git.parser.v2.read.ResolvedGitObjectRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.storage.local.LocalGitStorage;
import pro.deta.orion.git.parser.v2.storage.memory.InMemoryStorage;
import pro.deta.orion.git.parser.v2.storage.shared.PackDataStorage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
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
             GitIndexAccess index = memory ? new InMemoryIndex().createAccess() : new LocalGitIndex(directory).createAccess()) {
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
            try (GitStorageApi replay = new InMemoryStorage(); GitIndexAccess replayIndex = new InMemoryIndex().createAccess()) {
                PackMetadata copy = publish(bytes(completed, storage, index), replay, replayIndex);
                assertThat(copy.packChecksum()).isEqualTo(completed.packChecksum());
                assertThat(copy.objectCount()).isEqualTo(5);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void completesThinPackWithExternalBaseAndPersistsIt(int version) throws Exception {
        try (GitStorageApi storage = new LocalGitStorage(directory);
             GitIndexAccess index = new LocalGitIndex(directory).createAccess()) {
            ObjectId base = store(storage, index, GitObjectType.BLOB, new byte[]{1});
            byte[] source = pack(version, delta(base, new byte[]{1, 1, 1, 2}));
            PackMetadata completed = publish(source, storage, index);
            assertThat(completed.objectCount()).isEqualTo(2);
            try (GitStorageApi copy = new InMemoryStorage(); GitIndexAccess copyIndex = new InMemoryIndex().createAccess()) {
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
        try (GitStorageApi storage = new InMemoryStorage(); GitIndexAccess index = new InMemoryIndex().createAccess()) {
            ObjectId base = store(storage, index, GitObjectType.BLOB, new byte[]{1});
            List<PackMetadata> before = index.packs();
            assertThatThrownBy(() -> ingest(pack(delta(base, new byte[]{1, 1, (byte) 0x90, 1})),
                    storage, index)).isInstanceOf(IOException.class).hasMessageContaining("cycle");
            assertThat(index.packs()).isEqualTo(before);
        }
    }

    @Test
    void doesNotAppendAPublishedBaseThatLaterResolvesInsideThePack() throws Exception {
        try (GitStorageApi storage = new InMemoryStorage(); GitIndexAccess index = new InMemoryIndex().createAccess()) {
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
        try (GitStorageApi storage = new InMemoryStorage(); GitIndexAccess index = new InMemoryIndex().createAccess()) {
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readsDeepForwardChainInLinearWork(boolean memory) throws Exception {
        try (CountingStorage storage = new CountingStorage(
                memory ? new InMemoryStorage() : new LocalGitStorage(directory));
             GitIndexAccess index = memory ? new InMemoryIndex().createAccess() : new LocalGitIndex(directory).createAccess()) {
            int depth = 1100;
            List<byte[]> entries = new ArrayList<>();
            for (int value = depth; value > 0; value--) {
                byte[] previous = {(byte) ((value - 1) >>> 8), (byte) (value - 1)};
                entries.add(delta(objectId(GitObjectType.BLOB, previous),
                        new byte[]{2, 2, 2, (byte) (value >>> 8), (byte) value}));
            }
            entries.add(blob(new byte[]{0, 0}));
            byte[] source = pack(entries.toArray(byte[][]::new));
            long started = System.nanoTime();
            PackMetadata completed = publish(source, storage, index);
            System.out.printf("Delta chain: depth=%d, memory=%s, reads=%d, elapsedMs=%d%n",
                    depth, memory, storage.reads, (System.nanoTime() - started) / 1_000_000);
            assertThat(storage.reads).isLessThanOrEqualTo(3 * depth + 3);
            assertThat(bytes(completed, storage, index)).containsExactly(source);
        }
    }

    @Test
    void reusesTheResolvedBaseAcrossManyBranches() throws Exception {
        try (CountingStorage storage = new CountingStorage(new InMemoryStorage());
             GitIndexAccess index = new InMemoryIndex().createAccess()) {
            List<byte[]> entries = new ArrayList<>();
            ObjectId base = objectId(GitObjectType.BLOB, new byte[]{0, 0});
            entries.add(blob(new byte[]{0, 0}));
            for (int value = 1; value <= 200; value++) {
                byte[] content = {(byte) (value >>> 8), (byte) value};
                entries.add(delta(base, join(new byte[]{2, 2, 2}, content)));
                base = objectId(GitObjectType.BLOB, content);
            }
            for (int value = 201; value <= 300; value++) {
                entries.add(delta(base, new byte[]{2, 2, 2, (byte) (value >>> 8), (byte) value}));
            }
            byte[] source = pack(entries.toArray(byte[][]::new));
            PackMetadata completed = publish(source, storage, index);
            assertThat(storage.reads).isLessThanOrEqualTo(3 * entries.size());
            assertThat(bytes(completed, storage, index)).containsExactly(source);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {3 * 1024 * 1024, 9 * 1024 * 1024})
    void rereadsEvictedOrOversizedExternalBasesAndProducesSelfContainedPack(int size) throws Exception {
        try (CountingStorage storage = new CountingStorage(new InMemoryStorage());
             GitIndexAccess index = new InMemoryIndex().createAccess()) {
            List<ObjectId> bases = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                byte[] content = new byte[size];
                content[0] = (byte) i;
                bases.add(store(storage, index, GitObjectType.BLOB, content));
            }
            PackId firstPack = index.locations(bases.getFirst()).getFirst().packId();
            storage.readsByPack.clear();
            List<byte[]> entries = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                ByteArrayOutputStream instructions = new ByteArrayOutputStream();
                int remaining = size;
                while (remaining >= 128) {
                    instructions.write((remaining & 127) | 128);
                    remaining >>>= 7;
                }
                instructions.write(remaining);
                instructions.writeBytes(new byte[]{1, 1, (byte) (10 + i)});
                entries.add(delta(bases.get(i % 4), instructions.toByteArray()));
            }
            PackMetadata completed = publish(pack(entries.toArray(byte[][]::new)), storage, index);
            assertThat(storage.readsByPack.get(firstPack)).isGreaterThanOrEqualTo(2);
            assertThat(completed.objectCount()).isEqualTo(9);
            try (GitStorageApi replay = new InMemoryStorage(); GitIndexAccess replayIndex = new InMemoryIndex().createAccess()) {
                PackMetadata copy = publish(bytes(completed, storage, index), replay, replayIndex);
                assertThat(copy.packChecksum()).isEqualTo(completed.packChecksum());
                for (int i = 0; i < 5; i++) {
                    byte[] expected = {(byte) (10 + i)};
                    assertThat(GitObjectRead.read(replay, replayIndex, objectId(GitObjectType.BLOB, expected),
                            new ResolvedGitObjectRead<>(replay, replayIndex,
                                    (type, length, base, input) -> input.readBytes((int) length))))
                            .hasValueSatisfying(content -> assertThat(content).containsExactly(expected));
                }
            }
        }
    }

    private static final class CountingStorage implements GitStorageApi {
        private final GitStorageApi backend;
        private int reads;
        private final Map<PackId, Integer> readsByPack = new HashMap<>();

        private CountingStorage(GitStorageApi backend) {
            this.backend = backend;
        }

        @Override
        public PackDataStorage newPack(PackId id) throws IOException {
            return backend.newPack(id);
        }

        @Override
        public <R> R readPack(PackId id, long offset, long length, GitPackRead<R> reader) throws IOException {
            reads++;
            readsByPack.merge(id, 1, Integer::sum);
            return backend.readPack(id, offset, length, reader);
        }

        @Override
        public boolean exists(PackId id) throws IOException {
            return backend.exists(id);
        }

        @Override
        public void close() throws IOException {
            backend.close();
        }
    }

    @Test
    void rejectsInvalidDeltaInstructionsBeforePublication() throws Exception {
        try (GitStorageApi storage = new InMemoryStorage(); GitIndexAccess index = new InMemoryIndex().createAccess()) {
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
        try (GitStorageApi storage = new InMemoryStorage(); GitIndexAccess index = new InMemoryIndex().createAccess()) {
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
