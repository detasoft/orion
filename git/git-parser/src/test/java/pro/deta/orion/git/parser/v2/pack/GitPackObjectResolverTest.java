package pro.deta.orion.git.parser.v2.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.read.ResolvedGitObjectRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static pro.deta.orion.git.parser.v2.pack.PackTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class GitPackObjectResolverTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"false, 2", "true, 2", "false, 3", "true, 3"})
    void resolvesForwardReferencesBranchesAndOffsetDeltasWithoutChangingEntryBytes(boolean memory, int version)
            throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        ObjectId first = objectId(GitObjectType.BLOB, new byte[]{1});
        ObjectId second = objectId(GitObjectType.BLOB, new byte[]{2});
        byte[] forward = delta(second, new byte[]{1, 1, 1, 3});
        byte[] offset = join(PackEntryWriter.objectHeader(GitObjectType.OFS_DELTA, 4),
                new byte[]{(byte) forward.length}, compressed(new byte[]{1, 1, 1, 4}));
        byte[] source = pack(version, forward, offset, delta(second, new byte[]{1, 1, 1, 5}),
                delta(first, new byte[]{1, 1, 1, 2}), blob(new byte[]{1}));
        try (IndexedPack target = ingest(source, memory ? IndexedPack.create() : storage.newPack())) {
            PackId received = target.checksum();
            assertThat(new GitPackObjectResolver(target, storage).complete()).isEqualTo(received);
            assertThat(bytes(target)).containsExactly(source);
            assertThat(target.objectCount()).isEqualTo(5);
            storage.persist(target);
        }
        for (byte value = 1; value <= 5; value++) {
            byte expected = value;
            assertThat(storage.readObject(objectId(GitObjectType.BLOB, new byte[]{value}),
                    new ResolvedGitObjectRead<>(storage, (type, size, base, input) -> input.readBytes((int) size))))
                    .hasValueSatisfying(content -> assertThat(content).containsExactly(expected));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void completesThinPackWithExternalBaseAndPersistsIt(int version) throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        ObjectId base = store(storage, GitObjectType.BLOB, new byte[]{1});
        byte[] source = pack(version, delta(base, new byte[]{1, 1, 1, 2}));
        try (IndexedPack target = ingest(source, storage.newPack())) {
            PackId received = target.id();
            PackId completed = new GitPackObjectResolver(target, storage).complete();
            assertThat(completed).isNotEqualTo(received).isEqualTo(target.checksum());
            assertThat(target.objectCount()).isEqualTo(2);
            assertThat(ByteBuffer.wrap(bytes(target)).getInt(4)).isEqualTo(version);
            storage.persist(target);
        }
        assertThat(storage.readObject(objectId(GitObjectType.BLOB, new byte[]{2}),
                new ResolvedGitObjectRead<>(storage, (type, size, unused, input) -> input.readBytes((int) size))))
                .hasValueSatisfying(content -> assertThat(content).containsExactly((byte) 2));
    }

    @Test
    void doesNotAppendAPublishedBaseThatLaterResolvesInsideThePack() throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        ObjectId base = store(storage, GitObjectType.BLOB, new byte[]{2});
        ObjectId root = objectId(GitObjectType.BLOB, new byte[]{1});
        byte[] source = pack(delta(base, new byte[]{1, 1, 1, 3}),
                delta(root, new byte[]{1, 1, 1, 2}), blob(new byte[]{1}));
        try (IndexedPack target = ingest(source, IndexedPack.create())) {
            PackId received = target.checksum();
            assertThat(new GitPackObjectResolver(target, storage).complete()).isEqualTo(received);
            assertThat(target.objectCount()).isEqualTo(3);
            assertThat(bytes(target)).containsExactly(source);
        }
    }

    @Test
    void resolvesADeepForwardChainWithoutRecursiveCalls() throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        List<byte[]> entries = new ArrayList<>();
        for (int value = 1100; value > 0; value--) {
            byte[] previous = {(byte) ((value - 1) >>> 8), (byte) (value - 1)};
            entries.add(delta(objectId(GitObjectType.BLOB, previous),
                    new byte[]{2, 2, 2, (byte) (value >>> 8), (byte) value}));
        }
        entries.add(blob(new byte[]{0, 0}));
        byte[] source = pack(entries.toArray(byte[][]::new));
        try (IndexedPack target = ingest(source, IndexedPack.create())) {
            assertTimeout(Duration.ofSeconds(30), () -> new GitPackObjectResolver(target, storage).complete());
            assertThat(target.objectCount()).isEqualTo(1101);
            assertThat(bytes(target)).containsExactly(source);
        }
    }

    @Test
    void rejectsInvalidDeltaInstructionsBeforePublication() throws Exception {
        GitStorageApi storage = new GitStorageApi(directory);
        byte[][] invalid = {{2, 0}, {1, 1, 0}, {1, 1, 1}, {1, 1, (byte) 0x91, 1, 1},
                {1, 1, (byte) 0x90, 2}, {1, 1}, {1, 0, 1, 42}, {1, 1, (byte) 0x91}};
        for (byte[] instructions : invalid) {
            byte[] source = pack(blob(new byte[]{1}),
                    delta(objectId(GitObjectType.BLOB, new byte[]{1}), instructions));
            try (IndexedPack target = ingest(source, IndexedPack.create())) {
                assertThatThrownBy(() -> new GitPackObjectResolver(target, storage).complete())
                        .isInstanceOf(IOException.class);
            }
        }
    }
}
