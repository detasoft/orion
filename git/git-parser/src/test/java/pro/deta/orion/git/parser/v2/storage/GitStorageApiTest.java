package pro.deta.orion.git.parser.v2.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.IndexedObject;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.git.local.LocalGitIndex;
import pro.deta.orion.git.parser.v2.index.memory.InMemoryIndex;
import pro.deta.orion.git.parser.v2.pack.PackTestData;
import pro.deta.orion.git.parser.v2.read.ContentGitObjectRead;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.git.parser.v2.storage.local.LocalGitStorage;
import pro.deta.orion.git.parser.v2.storage.memory.InMemoryStorage;
import pro.deta.orion.git.parser.v2.storage.shared.PackHandle;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitStorageApiTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void accessesOwnHandlesAndOutliveTheirClosedOwner(boolean disk) throws Exception {
        try (GitStorageApi owner = disk ? new LocalGitStorage(directory) : new InMemoryStorage()) {
            GitStorageAccess first = owner.createAccess();
            GitStorageAccess second = owner.createAccess();
            PackId id = PackId.create();
            PackHandle writer = first.newPack(id);
            writer.write(0, ByteBuffer.wrap(new byte[]{1, 2}));
            owner.close();
            assertThatThrownBy(owner::createAccess).isInstanceOf(IOException.class);
            first.close();
            first.close();
            assertThat(writer.isOpen()).isFalse();
            assertThatThrownBy(() -> first.exists(id)).isInstanceOf(IOException.class);
            assertThat(second.<byte[]>readPack(id, 0, 2, (length, input) -> input.readBytes(2)))
                    .containsExactly(1, 2);
            second.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readsExactCompressedRangesFromRepositoryLocations(boolean disk) throws Exception {
        {
            try (GitStorageAccess storage = disk ? new LocalGitStorage(directory).createAccess() : new InMemoryStorage().createAccess()) {
                GitIndexAccess index = disk ? new LocalGitIndex(directory).createAccess(Optional.of(PackId.create()))
                        : new InMemoryIndex().createAccess(Optional.of(PackId.create()));
                try {
                    byte[] first = {1, 2, 3};
                    byte[] second = {4, 5};
                    PackMetadata pair = PackTestData.publish(
                            PackTestData.pack(PackTestData.blob(first), PackTestData.blob(second)), storage, index);
                    List<IndexedObject> locations = index.objects(pair.packId());
                    assertThat(locations).hasSize(2);
                    assertThat(locations.getFirst().packOffset() + locations.getFirst().compressedSize())
                            .isEqualTo(locations.getLast().packOffset());
                    byte[][] contents = {first, second};
                    for (int i = 0; i < contents.length; i++) {
                        byte[] expected = contents[i];
                        byte[] compressed = GitObjectRead.read(storage, locations.get(i), (type, size, base, input) -> {
                            assertThat(type).isEqualTo(GitObjectType.BLOB);
                            assertThat(size).isEqualTo(expected.length);
                            assertThat(base).isEmpty();
                            return input.newInputStream().readAllBytes();
                        });
                        assertThat(compressed).isEqualTo(PackTestData.compressed(expected));
                        assertThat(GitObjectRead.read(storage, index, locations.get(i).objectId(),
                                new ContentGitObjectRead<>((type, size, base, input) -> input.readBytes((int) size))))
                                .hasValueSatisfying(bytes -> assertThat(bytes).isEqualTo(expected));
                    }
                } finally {
                    index.discard();
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void borrowsIndependentInputsAndReleasesLocksBeforeCallbacks(boolean disk) throws Exception {
        try (GitStorageAccess storage =
                (disk ? new LocalGitStorage(directory).createAccess() : new InMemoryStorage().createAccess());
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            byte[] expected = new byte[40_000];
            new Random(81).nextBytes(expected);
            PackId id = PackId.create();
            try (PackHandle writer = storage.newPack(id)) {
                writer.write(0, ByteBuffer.wrap(expected));
                writer.flush();
            }
            AtomicReference<BufferedByteInputV2> borrowed = new AtomicReference<>();
            byte[] actual = storage.readPack(id, 0, expected.length, (size, input) -> {
                borrowed.set(input);
                assertThat(size).isEqualTo(expected.length);
                try {
                    byte[] nested = executor.submit(() -> storage.readPack(id, 0, 4,
                            (length, source) -> source.readBytes(4))).get(5, TimeUnit.SECONDS);
                    assertThat(nested).containsExactly(Arrays.copyOf(expected, 4));
                } catch (Exception failure) {
                    throw new IOException(failure);
                }
                return input.newInputStream().readAllBytes();
            });
            assertThat(actual).isEqualTo(expected);
            assertThatThrownBy(() -> borrowed.get().readUnsignedByte()).isInstanceOf(IOException.class);
            IOException failure = new IOException("reader failed");
            assertThatThrownBy(() -> storage.readPack(id, 0, 1, (size, input) -> {
                borrowed.set(input);
                throw failure;
            })).isSameAs(failure);
            assertThatThrownBy(() -> borrowed.get().readUnsignedByte()).isInstanceOf(IOException.class);
            assertThat(storage.<byte[]>readPack(id, 0, expected.length,
                    (size, input) -> input.newInputStream().readAllBytes())).isEqualTo(expected);
            assertThatThrownBy(() -> storage.readPack(id, 1, expected.length, (size, input) -> 1))
                    .isInstanceOf(IOException.class);
            assertThat(storage.exists(PackId.create())).isFalse();
            assertThatThrownBy(() -> storage.readPack(PackId.create(), 0, 1, (size, input) -> {
                throw new AssertionError("Missing bytes must not invoke a reader");
            })).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> storage.newPack(id)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void rawReadRemainsAvailableAfterIndexClosesAndMissingObjectsDoNotInvokeReader() throws Exception {
        {
            try (GitStorageAccess storage = new LocalGitStorage(directory).createAccess()) {
                new LocalGitIndex(directory).withAccess(Optional.of(PackId.create()), index -> {
                    ObjectId absent = new ObjectId("1".repeat(40));
                    assertThat(GitObjectRead.exists(storage, index, absent)).isFalse();
                    assertThat(GitObjectRead.read(storage, index, absent, (type, size, base, input) -> {
                        throw new AssertionError("Missing object must not invoke a reader");
                    })).isEmpty();
                    ObjectId object = PackTestData.store(storage, index, GitObjectType.BLOB, new byte[]{42});
                    IndexedObject location = index.locations(object).getFirst();
                    index.discard();
                    assertThat(storage.exists(location.packId())).isTrue();
                    assertThat(GitObjectRead.<Integer>read(storage, location,
                            new ContentGitObjectRead<>((type, size, base, input) -> input.readUnsignedByte())))
                            .isEqualTo(42);
                    return null;
                });
            }
        }
    }
}
