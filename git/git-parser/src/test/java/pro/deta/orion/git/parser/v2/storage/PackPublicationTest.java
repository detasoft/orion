package pro.deta.orion.git.parser.v2.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.index.GitIndexApi;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.git.parser.v2.index.local.LocalGitIndex;
import pro.deta.orion.git.parser.v2.index.memory.InMemoryIndex;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.git.parser.v2.read.ResolvedGitObjectRead;
import pro.deta.orion.git.parser.v2.storage.local.LocalGitStorage;
import pro.deta.orion.git.parser.v2.storage.memory.InMemoryStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.pack.PackTestData.*;

class PackPublicationTest {
    @TempDir
    Path directory;

    @Test
    void keepsPendingObjectsPrivateAcrossReopenThenPublishesAllOfThem() throws Exception {
        PackMetadata metadata;
        ObjectId id = objectId(GitObjectType.BLOB, new byte[]{1});
        try (GitStorageApi storage = new LocalGitStorage(directory); GitIndexApi index = new LocalGitIndex(directory)) {
            metadata = ingest(pack(blob(new byte[]{1})), storage, index);
        }
        try (GitStorageApi storage = new LocalGitStorage(directory); GitIndexApi index = new LocalGitIndex(directory)) {
            assertThat(storage.exists(metadata.packId())).isTrue();
            assertThat(index.findObject(metadata.packId(), id)).isPresent();
            assertThat(index.locations(id)).isEmpty();
            assertThat(index.packs()).isEmpty();
            index.publishIndex(metadata);
        }
        try (GitStorageApi storage = new LocalGitStorage(directory); GitIndexApi index = new LocalGitIndex(directory)) {
            assertThat(index.packs(metadata.packChecksum())).containsExactly(metadata);
            assertThat(read(storage, index, id)).containsExactly(1);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentIdenticalLoadsPublishIndependentPacks(boolean memory) throws Exception {
        try (GitStorageApi storage = memory ? new InMemoryStorage() : new LocalGitStorage(directory);
             GitIndexApi index = memory ? new InMemoryIndex() : new LocalGitIndex(directory);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            byte[] input = pack(blob(new byte[]{1}));
            CyclicBarrier start = new CyclicBarrier(2);
            var first = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return publish(input, storage, index);
            });
            var second = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                if (memory) {
                    return publish(input, storage, index);
                }
                try (GitStorageApi otherStorage = new LocalGitStorage(directory);
                     GitIndexApi otherIndex = new LocalGitIndex(directory)) {
                    return publish(input, otherStorage, otherIndex);
                }
            });
            PackMetadata a = first.get(15, TimeUnit.SECONDS);
            PackMetadata b = second.get(15, TimeUnit.SECONDS);
            assertThat(a.packId()).isNotEqualTo(b.packId());
            assertThat(a.packChecksum()).isEqualTo(b.packChecksum());
            assertThat(index.packs(a.packChecksum())).containsExactlyInAnyOrder(a, b);
            assertThat(index.locations(objectId(GitObjectType.BLOB, new byte[]{1}))).hasSize(2);
        }
    }

    @Test
    void missingPhysicalPackIsNotAnExistingRefTargetAndReadReportsFailure() throws Exception {
        try (GitStorageApi storage = new LocalGitStorage(directory); GitIndexApi index = new LocalGitIndex(directory)) {
            ObjectId id = store(storage, index, GitObjectType.BLOB, new byte[]{1});
            PackMetadata metadata = index.packs().getFirst();
            Files.delete(directory.resolve("packs").resolve("pack-" + metadata.packId() + ".data"));
            assertThat(index.locations(id)).hasSize(1);
            assertThat(GitObjectRead.exists(storage, index, id)).isFalse();
            assertThatThrownBy(() -> read(storage, index, id)).isInstanceOf(IOException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resolvesTwoExternalBasesAndReadsDeepPublishedChains(boolean memory) throws Exception {
        try (GitStorageApi storage = memory ? new InMemoryStorage() : new LocalGitStorage(directory);
             GitIndexApi index = memory ? new InMemoryIndex() : new LocalGitIndex(directory)) {
            ObjectId first = store(storage, index, GitObjectType.BLOB, new byte[]{1});
            ObjectId second = store(storage, index, GitObjectType.BLOB, new byte[]{2});
            PackMetadata thin = publish(pack(delta(first, new byte[]{1, 1, 1, 3}),
                    delta(second, new byte[]{1, 1, 1, 4})), storage, index);
            assertThat(thin.objectCount()).isEqualTo(4);
            try (GitStorageApi receiver = new InMemoryStorage(); GitIndexApi received = new InMemoryIndex()) {
                PackMetadata replay = publish(bytes(thin, storage, index), receiver, received);
                assertThat(replay.packChecksum()).isEqualTo(thin.packChecksum());
                assertThat(read(receiver, received, objectId(GitObjectType.BLOB, new byte[]{4})))
                        .containsExactly(4);
            }
            List<byte[]> entries = new ArrayList<>();
            byte[] value = java.nio.ByteBuffer.allocate(4).putInt(0).array();
            entries.add(blob(value));
            ObjectId previous = objectId(GitObjectType.BLOB, value);
            for (int i = 1; i <= 200; i++) {
                value = java.nio.ByteBuffer.allocate(4).putInt(i).array();
                entries.add(delta(previous, join(new byte[]{4, 4, 4}, value)));
                previous = objectId(GitObjectType.BLOB, value);
            }
            publish(pack(entries.toArray(byte[][]::new)), storage, index);
            assertThat(read(storage, index, previous)).containsExactly(value);
        }
    }

    private static byte[] read(GitStorageApi storage, GitIndexApi index, ObjectId id) throws IOException {
        return GitObjectRead.read(storage, index, id, new ResolvedGitObjectRead<>(storage, index,
                (type, size, base, input) -> input.readBytes((int) size))).orElseThrow();
    }
}
