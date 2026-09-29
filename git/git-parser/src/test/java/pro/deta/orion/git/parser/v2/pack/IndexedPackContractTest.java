package pro.deta.orion.git.parser.v2.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.read.ContentGitObjectRead;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.git.parser.v2.read.ResolvedGitObjectRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.storage.local.LocalGitStorage;
import pro.deta.orion.git.parser.v2.storage.memory.InMemoryStorage;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexedPackContractTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completesExternalDeltaThroughPackContract(boolean disk) throws Exception {
        try (GitStorageApi storage = disk ? new LocalGitStorage(directory) : new InMemoryStorage()) {
            byte[] base = {1, 2, 3};
            ObjectId baseId = PackTestData.store(storage, GitObjectType.BLOB, base);
            byte[] result = {1, 2, 3, 4};
            ObjectId resultId = PackTestData.objectId(GitObjectType.BLOB, result);
            byte[] received = PackTestData.pack(PackTestData.delta(baseId, new byte[]{3, 4, -112, 3, 1, 4}));
            try (ContractPack pack = new ContractPack(storage.newPack())) {
                assertThat(PackTestData.ingest(received, pack)).isSameAs(pack);
                PackId receivedId = pack.id();
                PackId completedId = new GitPackObjectResolver(pack, storage).complete();
                assertThat(completedId).isNotEqualTo(receivedId);
                assertThat(pack.checksumMatches(completedId)).isTrue();
                assertThat(pack.objectIds()).containsExactlyInAnyOrder(baseId, resultId);
                assertThat(pack.uploadClosed).isTrue();
                assertThat(pack.closed).isFalse();
                IndexedPack replay = PackTestData.ingest(PackTestData.bytes(pack), storage.newPack());
                assertThat(new GitPackObjectResolver(replay, storage).complete()).isEqualTo(completedId);
                storage.persist(replay);
                assertThat(storage.readObject(resultId, new ResolvedGitObjectRead<byte[]>(storage,
                        (type, size, baseObject, input) -> input.readBytes((int) size))))
                        .hasValueSatisfying(bytes -> assertThat(bytes).containsExactly(result));
            }
        }
    }

    @Test
    void ingestsFullObjectAndLeavesSuccessfulTargetOwnedByCaller() throws Exception {
        byte[] content = {1, 2, 3};
        ObjectId id = PackTestData.objectId(GitObjectType.BLOB, content);
        try (ContractPack pack = new ContractPack(new InMemoryStorage().newPack())) {
            assertThat(PackTestData.ingest(PackTestData.pack(PackTestData.blob(content)), pack)).isSameAs(pack);
            assertThat(pack.closed).isFalse();
            assertThat(pack.readObject(id, new ContentGitObjectRead<byte[]>(
                    (type, size, base, input) -> input.readBytes((int) size))))
                    .hasValueSatisfying(bytes -> assertThat(bytes).containsExactly(content));
        }
    }

    @Test
    void checksumFailureDiscardsIndependentTarget() throws Exception {
        byte[] bytes = PackTestData.pack(PackTestData.blob(new byte[]{1, 2, 3}));
        bytes[bytes.length - 1] ^= 1;
        ContractPack pack = new ContractPack(new InMemoryStorage().newPack());
        assertThatThrownBy(() -> PackTestData.ingest(bytes, pack))
                .isInstanceOf(IOException.class).hasMessageContaining("checksum mismatch");
        assertThat(pack.discarded).isTrue();
        assertThatThrownBy(pack::size).isInstanceOf(ClosedChannelException.class);
    }

    @Test
    void incompatiblePublicationLeavesThePackOwnedByCaller() throws Exception {
        try (GitStorageApi storage = new InMemoryStorage();
             ContractPack pack = new ContractPack(new InMemoryStorage().newPack())) {
            PackTestData.ingest(PackTestData.pack(PackTestData.blob(new byte[]{1, 2, 3})), pack);
            new GitPackObjectResolver(pack, storage).complete();
            assertThatThrownBy(() -> storage.persist(pack)).isInstanceOf(IllegalArgumentException.class);
            assertThat(pack.closed).isFalse();
            assertThat(pack.discarded).isFalse();
            assertThat(pack.size()).isPositive();
            assertThat(storage.packIds()).isEmpty();
        }
    }

    @Test
    void missingDeltaBaseClosesResolutionStateAndPack() throws Exception {
        try (GitStorageApi storage = new InMemoryStorage()) {
            ObjectId missing = PackTestData.objectId(GitObjectType.BLOB, new byte[]{1});
            ContractPack pack = new ContractPack(new InMemoryStorage().newPack());
            PackTestData.ingest(PackTestData.pack(PackTestData.delta(missing, new byte[]{1, 1, 1, 2})), pack);
            assertThatThrownBy(() -> new GitPackObjectResolver(pack, storage).complete())
                    .isInstanceOf(IOException.class).hasMessageContaining("unresolved");
            assertThat(pack.uploadClosed).isTrue();
            assertThat(pack.closed).isTrue();
            assertThatThrownBy(pack::size).isInstanceOf(ClosedChannelException.class);
            assertThat(storage.packIds()).isEmpty();
        }
    }

    private static final class ContractPack implements IndexedPack {
        private final IndexedPack delegate;
        private boolean closed;
        private boolean discarded;
        private boolean uploadClosed;

        private ContractPack(IndexedPack delegate) {
            this.delegate = delegate;
        }

        @Override
        public void append(ByteBuffer source) throws IOException {
            delegate.append(source);
        }

        @Override
        public void write(long offset, ByteBuffer source) throws IOException {
            delegate.write(offset, source);
        }

        @Override
        public int read(long offset, ByteBuffer target) throws IOException {
            return delegate.read(offset, target);
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public void truncate(long size) throws IOException {
            delegate.truncate(size);
        }

        @Override
        public PackId id() throws IOException {
            return delegate.id();
        }

        @Override
        public boolean checksumMatches(PackId expected) throws IOException {
            return delegate.checksumMatches(expected);
        }

        @Override
        public <R> R readObject(long offset, GitObjectRead<R> reader) throws IOException {
            return delegate.readObject(offset, reader);
        }

        @Override
        public <R> Optional<R> readObject(ObjectId id, GitObjectRead<R> reader) throws IOException {
            return delegate.readObject(id, reader);
        }

        @Override
        public long dataEnd(long offset) throws IOException {
            return delegate.dataEnd(offset);
        }

        @Override
        public Optional<ObjectId> baseId(long offset) throws IOException {
            return delegate.baseId(offset);
        }

        @Override
        public <R> R readObject(EntryMetadata entry, long end, Optional<ObjectId> baseId,
                               GitObjectRead<R> reader) throws IOException {
            return delegate.readObject(entry, end, baseId, reader);
        }

        @Override
        public boolean addEntry(long offset, long dataOffset, long inflatedSize, GitObjectType type,
                                OptionalLong baseOffset, Optional<ObjectId> baseId) throws IOException {
            return delegate.addEntry(offset, dataOffset, inflatedSize, type, baseOffset, baseId);
        }

        @Override
        public boolean addObject(long offset, ObjectId id, GitObjectType type, long size)
                throws IOException {
            return delegate.addObject(offset, id, type, size);
        }

        @Override
        public Optional<EntryMetadata> find(ObjectId id) throws IOException {
            return delegate.find(id);
        }

        @Override
        public Optional<EntryMetadata> find(long offset) throws IOException {
            return delegate.find(offset);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            delegate.close();
        }

        @Override
        public void discard() throws IOException {
            discarded = true;
            delegate.discard();
        }

        @Override
        public long entryCount() {
            return delegate.entryCount();
        }

        @Override
        public long objectCount() {
            return delegate.objectCount();
        }

        @Override
        public Set<ObjectId> objectIds() {
            return delegate.objectIds();
        }

        @Override
        public BufferedByteInputV2 input() throws IOException {
            return delegate.input();
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void setId(PackId id) throws IOException {
            delegate.setId(id);
        }

        @Override
        public PackId finish(long dataEnd) throws IOException {
            return delegate.finish(dataEnd);
        }

        @Override
        public Iterator<Long> offsets() {
            return delegate.offsets();
        }

        @Override
        public Long objectOffset(ObjectId id) {
            return delegate.objectOffset(id);
        }

        @Override
        public Record record(long offset) throws IOException {
            return delegate.record(offset);
        }

        @Override
        public void requireMutable() throws IOException {
            delegate.requireMutable();
        }

        @Override
        public PackUploadIndex newUploadIndex() throws IOException {
            PackUploadIndex index = delegate.newUploadIndex();
            return new PackUploadIndex() {
                @Override
                public Optional<ObjectId> nextExternalBase() throws IOException {
                    return index.nextExternalBase();
                }

                @Override
                public void finish() throws IOException {
                    index.finish();
                }

                @Override
                public void addEntry(IndexedPack.EntryMetadata entry) throws IOException {
                    index.addEntry(entry);
                }

                @Override
                public void addObject(IndexedPack.EntryMetadata entry, ObjectId id, GitObjectType type, long size)
                        throws IOException {
                    index.addObject(entry, id, type, size);
                }

                @Override
                public Optional<IndexedPack.EntryMetadata> waitingFor(ObjectId id, long offset) throws IOException {
                    return index.waitingFor(id, offset);
                }

                @Override
                public boolean hasUnresolved() throws IOException {
                    return index.hasUnresolved();
                }

                @Override
                public void close() throws IOException {
                    uploadClosed = true;
                    index.close();
                }
            };
        }
    }
}
