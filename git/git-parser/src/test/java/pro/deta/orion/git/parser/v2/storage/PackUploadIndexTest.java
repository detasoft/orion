package pro.deta.orion.git.parser.v2.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.pack.PackTestData;
import pro.deta.orion.git.parser.v2.pack.PackUploadIndex;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackUploadIndexTest {
    @TempDir
    Path directory;

    @Test
    void registersPhysicalEntriesAndCompletesThemWithoutLosingDuplicateObjectOffsets() throws Exception {
        Path path = directory.resolve("incoming.index");
        var first = full(12);
        var duplicate = full(64);
        try (IndexedPack pack = create(path); PackUploadIndex index = PackUploadIndex.create(pack)) {
            assertThat(index.hasUnresolved()).isFalse();
            assertThat(pack.find(12)).isEmpty();
            assertThat(pack.find(id(1))).isEmpty();
            index.addEntry(first);
            assertThat(pack.find(12)).contains(first);
            assertThat(pack.find(id(1))).isEmpty();
            assertThat(index.hasUnresolved()).isTrue();
            index.addObject(first, id(1), GitObjectType.BLOB, 3);
            index.addEntry(duplicate);
            index.addObject(duplicate, id(1), GitObjectType.BLOB, 3);
            index.addObject(first, id(1), GitObjectType.BLOB, 3);
            index.addEntry(first);
            assertThat(index.hasUnresolved()).isFalse();
            assertThat(pack.find(id(1))).contains(first);
            assertThat(pack.find(12)).contains(first);
            assertThat(pack.find(64)).contains(duplicate);
            index.finish();
        }
        try (IndexedPack pack = IndexedPack.open(path.resolve("data.pack"), path.resolve("data.mv"))) {
            assertThat(pack.find(12)).contains(first);
            assertThat(pack.find(64)).contains(duplicate);
            assertThat(pack.find(id(1))).contains(first);
        }
    }

    @Test
    void walksWaitingBranchesAndChainsOneDependentAtATime() throws Exception {
        try (IndexedPack pack = create(directory.resolve("incoming.index")); PackUploadIndex index = PackUploadIndex.create(pack)) {
            var base = full(12);
            var byId = ref(64, id(1));
            var byOffset = ofs(128, 12);
            var child = ref(192, id(2));
            for (var entry : List.of(base, byId, byOffset, child)) {
                index.addEntry(entry);
            }
            index.addObject(base, id(1), GitObjectType.BLOB, 3);
            assertThat(index.waitingFor(id(1), 12)).contains(byId);
            assertThat(index.waitingFor(id(1), 12)).contains(byId);
            index.addObject(byId, id(2), GitObjectType.BLOB, 99);
            assertThat(index.waitingFor(id(1), 12)).contains(byOffset);
            assertThat(index.waitingFor(id(2), 64)).contains(child);
            index.addObject(byOffset, id(3), GitObjectType.BLOB, 100);
            assertThat(index.waitingFor(id(1), 12)).isEmpty();
            assertThat(index.hasUnresolved()).isTrue();
            index.addObject(child, id(4), GitObjectType.BLOB, 101);
            assertThat(index.waitingFor(id(2), 64)).isEmpty();
            assertThat(index.hasUnresolved()).isFalse();
            index.finish();
        }
    }

    @Test
    void suppliesMissingBasesOneAtATimeAndRequiresTheirRegistrationBeforeFinalization() throws Exception {
        Path path = directory.resolve("incoming.mv");
        var lateInternal = full(256);
        var appendedBase = full(320);
        try (IndexedPack pack = create(path); PackUploadIndex index = PackUploadIndex.create(pack)) {
            for (var entry : List.of(ref(12, id(10)), ref(64, id(20)), ref(128, id(20)))) {
                index.addEntry(entry);
                index.addObject(entry, id((int) entry.offset()), GitObjectType.BLOB, 99);
            }
            index.addEntry(lateInternal);
            index.addObject(lateInternal, id(10), GitObjectType.BLOB, 3);
            assertThat(index.nextExternalBase()).contains(id(20));
            assertThat(index.nextExternalBase()).contains(id(20));
            assertThatThrownBy(index::finish).isInstanceOf(IOException.class).hasMessageContaining("external");
            index.addEntry(appendedBase);
            index.addObject(appendedBase, id(20), GitObjectType.BLOB, 3);
            assertThat(index.nextExternalBase()).isEmpty();
            index.finish();
        }
        try (IndexedPack pack = IndexedPack.open(path.resolve("data.pack"), path.resolve("data.mv"))) {
            assertThat(pack.find(id(10))).contains(lateInternal);
            assertThat(pack.find(id(20))).contains(appendedBase);
            assertThat(pack.find(64).orElseThrow().baseId()).contains(id(20));
        }
    }

    @Test
    void refusesToFinalizeCyclesWithoutEvidenceOfWhichExternalBaseBreaksThem() throws Exception {
        try (IndexedPack pack = create(directory.resolve("self.index")); PackUploadIndex index = PackUploadIndex.create(pack)) {
            var self = ref(12, id(1));
            index.addEntry(self);
            index.addObject(self, id(1), GitObjectType.BLOB, 3);
            assertThatThrownBy(index::finish).isInstanceOf(IOException.class).hasMessageContaining("cycle");
            assertThat(index.hasUnresolved()).isFalse();
            assertThatThrownBy(index::finish).isInstanceOf(IOException.class).hasMessageContaining("cycle");
        }
        try (IndexedPack pack = create(directory.resolve("mixed.index")); PackUploadIndex index = PackUploadIndex.create(pack)) {
            var first = ref(12, id(2));
            var second = ofs(64, 12);
            index.addEntry(first);
            index.addObject(first, id(1), GitObjectType.BLOB, 3);
            index.addEntry(second);
            index.addObject(second, id(2), GitObjectType.BLOB, 3);
            assertThatThrownBy(index::finish).isInstanceOf(IOException.class).hasMessageContaining("cycle");
        }
    }

    @Test
    void finalizesDeepForwardChainsWithoutAnInMemoryTraversalStack() throws Exception {
        Path path = directory.resolve("deep.index");
        try (IndexedPack pack = create(path); PackUploadIndex index = PackUploadIndex.create(pack)) {
            for (int i = 1; i <= 2000; i++) {
                var entry = i == 2000 ? full(12L + 64L * i) : ref(12L + 64L * i, id(i + 1));
                index.addEntry(entry);
                index.addObject(entry, id(i), GitObjectType.BLOB, 3);
            }
            index.finish();
        }
        try (IndexedPack pack = IndexedPack.open(path.resolve("data.pack"), path.resolve("data.mv"))) {
            assertThat(pack.find(id(1))).contains(ref(76, id(2)));
        }
    }

    @Test
    void rejectsConflictingCompletionWithoutRemovingTheWaitingDependency() throws Exception {
        try (IndexedPack pack = create(directory.resolve("incoming.index")); PackUploadIndex index = PackUploadIndex.create(pack)) {
            var base = full(12);
            var delta = ref(64, id(1));
            index.addEntry(base);
            index.addObject(base, id(1), GitObjectType.BLOB, 3);
            index.addEntry(delta);
            assertThatThrownBy(() -> index.addObject(delta, id(1), GitObjectType.BLOB, 4))
                    .isInstanceOf(IOException.class);
            assertThat(index.waitingFor(id(1), 12)).contains(delta);
            assertThat(index.hasUnresolved()).isTrue();
            index.addObject(delta, id(2), GitObjectType.BLOB, 4);
            assertThatThrownBy(() -> index.addObject(delta, id(3), GitObjectType.BLOB, 4))
                    .isInstanceOf(IOException.class);
            assertThatThrownBy(() -> index.addObject(delta, id(2), GitObjectType.TREE, 4))
                    .isInstanceOf(IOException.class);
            assertThatThrownBy(() -> index.addObject(full(128), id(4), GitObjectType.BLOB, 3))
                    .isInstanceOf(IOException.class);
            assertThat(pack.find(id(2))).contains(delta);
            assertThat(pack.find(id(3))).isEmpty();
            assertThat(index.hasUnresolved()).isFalse();
        }
    }

    @Test
    void rejectsMalformedMetadataAndFullObjectCompletionWithDifferentTypeOrSize() throws Exception {
        try (IndexedPack pack = create(directory.resolve("incoming.index")); PackUploadIndex index = PackUploadIndex.create(pack)) {
            for (var invalid : List.of(new IndexedPack.EntryMetadata(12, 12, 3, GitObjectType.BLOB,
                            OptionalLong.empty(), Optional.empty()),
                    new IndexedPack.EntryMetadata(12, 13, -1, GitObjectType.BLOB,
                            OptionalLong.empty(), Optional.empty()),
                    new IndexedPack.EntryMetadata(12, 13, 3, GitObjectType.BLOB,
                            OptionalLong.empty(), Optional.of(id(1))),
                    new IndexedPack.EntryMetadata(12, 13, 3, GitObjectType.REF_DELTA,
                            OptionalLong.empty(), Optional.empty()), ofs(12, 12))) {
                assertThatThrownBy(() -> index.addEntry(invalid)).isInstanceOf(IOException.class);
            }
            assertThat(index.hasUnresolved()).isFalse();
            var entry = full(12);
            index.addEntry(entry);
            assertThatThrownBy(() -> index.addEntry(ref(12, id(1)))).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> index.addObject(entry, id(1), GitObjectType.TREE, 3))
                    .isInstanceOf(IOException.class);
            assertThatThrownBy(() -> index.addObject(entry, id(1), GitObjectType.BLOB, 4))
                    .isInstanceOf(IOException.class);
            assertThatThrownBy(() -> index.addObject(entry, id(1), GitObjectType.REF_DELTA, 3))
                    .isInstanceOf(IOException.class);
            assertThat(index.hasUnresolved()).isTrue();
            index.addObject(entry, id(1), GitObjectType.BLOB, 3);
            assertThat(index.hasUnresolved()).isFalse();
        }
    }

    @Test
    void cannotFinalizeUnresolvedStateAndFreezesAllMutationsAfterSuccessfulFinalization() throws Exception {
        Path path = directory.resolve("incoming.index");
        var entry = full(12);
        try (IndexedPack pack = create(path); PackUploadIndex index = PackUploadIndex.create(pack)) {
            index.addEntry(entry);
            assertThatThrownBy(index::finish).isInstanceOf(IOException.class);
            index.addObject(entry, id(1), GitObjectType.BLOB, 3);
            index.finish();
            index.finish();
            assertThatThrownBy(() -> index.addEntry(full(64))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> index.addObject(entry, id(1), GitObjectType.BLOB, 3))
                    .isInstanceOf(IllegalStateException.class);
        }
        try (IndexedPack pack = IndexedPack.open(path.resolve("data.pack"), path.resolve("data.mv"))) {
            assertThat(pack.find(id(1))).contains(entry);
        }
    }

    private static IndexedPack create(Path path) throws IOException {
        IndexedPack pack = IndexedPack.create(path);
        pack.append(ByteBuffer.wrap(PackTestData.pack()));
        return pack;
    }

    private static IndexedPack.EntryMetadata full(long offset) {
        return new IndexedPack.EntryMetadata(offset, offset + 1, 3, GitObjectType.BLOB,
                OptionalLong.empty(), Optional.empty());
    }

    private static IndexedPack.EntryMetadata ref(long offset, ObjectId base) {
        return new IndexedPack.EntryMetadata(offset, offset + 21, 3, GitObjectType.REF_DELTA,
                OptionalLong.empty(), Optional.of(base));
    }

    private static IndexedPack.EntryMetadata ofs(long offset, long base) {
        return new IndexedPack.EntryMetadata(offset, offset + 2, 3, GitObjectType.OFS_DELTA,
                OptionalLong.of(base), Optional.empty());
    }

    private static ObjectId id(int number) {
        return new ObjectId(ByteBuffer.allocate(20).putInt(16, number).array());
    }

}
