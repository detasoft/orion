package pro.deta.orion.git.parser.v2.storage;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.pack.PackTestData;
import pro.deta.orion.git.parser.v2.storage.local.LocalIndexedPack;
import pro.deta.orion.git.parser.v2.storage.memory.InMemoryStorage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackDependenciesTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void registersPhysicalEntriesAndCompletesThemWithoutLosingDuplicateObjectOffsets(boolean memory) throws Exception {
        Path path = directory.resolve("incoming.index");
        IndexedPack.EntryMetadata first = full(12);
        IndexedPack.EntryMetadata duplicate = full(64);
        try (IndexedPack pack = create(memory, path)) {
            assertThat(pack.hasUnresolved()).isFalse();
            assertThat(pack.find(12)).isEmpty();
            assertThat(pack.find(id(1))).isEmpty();
            addEntry(pack, first);
            assertThat(pack.find(12)).contains(first);
            assertThat(pack.find(id(1))).isEmpty();
            assertThat(pack.hasUnresolved()).isTrue();
            pack.addObject(first.offset(), id(1), GitObjectType.BLOB, 3);
            addEntry(pack, duplicate);
            pack.addObject(duplicate.offset(), id(1), GitObjectType.BLOB, 3);
            pack.addObject(first.offset(), id(1), GitObjectType.BLOB, 3);
            addEntry(pack, first);
            assertThat(pack.hasUnresolved()).isFalse();
            assertThat(pack.find(id(1))).contains(first);
            assertThat(pack.find(12)).contains(first);
            assertThat(pack.find(64)).contains(duplicate);
            pack.finish(12);
        }
        if (!memory) {
            try (IndexedPack pack = LocalIndexedPack.open(path.resolve("data.pack"), path.resolve("data.mv"))) {
                assertThat(pack.find(12)).contains(first);
                assertThat(pack.find(64)).contains(duplicate);
                assertThat(pack.find(id(1))).contains(first);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void walksWaitingBranchesAndChainsOneDependentAtATime(boolean memory) throws Exception {
        try (IndexedPack pack = create(memory, directory.resolve("incoming.index"))) {
            IndexedPack.EntryMetadata base = full(12);
            IndexedPack.EntryMetadata byId = ref(64, id(1));
            IndexedPack.EntryMetadata byOffset = ofs(128, 12);
            IndexedPack.EntryMetadata child = ref(192, id(2));
            for (IndexedPack.EntryMetadata entry : List.of(base, byId, byOffset, child)) {
                addEntry(pack, entry);
            }
            pack.addObject(base.offset(), id(1), GitObjectType.BLOB, 3);
            assertThat(pack.waitingFor(id(1), 12)).contains(byId);
            assertThat(pack.waitingFor(id(1), 12)).contains(byId);
            pack.addObject(byId.offset(), id(2), GitObjectType.BLOB, 99);
            assertThat(pack.waitingFor(id(1), 12)).contains(byOffset);
            assertThat(pack.waitingFor(id(2), 64)).contains(child);
            pack.addObject(byOffset.offset(), id(3), GitObjectType.BLOB, 100);
            assertThat(pack.waitingFor(id(1), 12)).isEmpty();
            assertThat(pack.hasUnresolved()).isTrue();
            pack.addObject(child.offset(), id(4), GitObjectType.BLOB, 101);
            assertThat(pack.waitingFor(id(2), 64)).isEmpty();
            assertThat(pack.hasUnresolved()).isFalse();
            pack.finish(12);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void suppliesMissingBasesOneAtATimeAndRequiresTheirRegistrationBeforeFinalization(boolean memory) throws Exception {
        Path path = directory.resolve("incoming.mv");
        IndexedPack.EntryMetadata lateInternal = full(256);
        IndexedPack.EntryMetadata appendedBase = full(320);
        try (IndexedPack pack = create(memory, path)) {
            for (IndexedPack.EntryMetadata entry : List.of(ref(12, id(10)), ref(64, id(20)), ref(128, id(20)))) {
                addEntry(pack, entry);
                pack.addObject(entry.offset(), id((int) entry.offset()), GitObjectType.BLOB, 99);
            }
            addEntry(pack, lateInternal);
            pack.addObject(lateInternal.offset(), id(10), GitObjectType.BLOB, 3);
            assertThat(pack.nextExternalBase()).contains(id(20));
            assertThat(pack.nextExternalBase()).contains(id(20));
            assertThatThrownBy(() -> pack.finish(12)).isInstanceOf(IOException.class).hasMessageContaining("external");
            addEntry(pack, appendedBase);
            pack.addObject(appendedBase.offset(), id(20), GitObjectType.BLOB, 3);
            assertThat(pack.nextExternalBase()).isEmpty();
            pack.finish(12);
        }
        if (!memory) {
            try (IndexedPack pack = LocalIndexedPack.open(path.resolve("data.pack"), path.resolve("data.mv"))) {
                assertThat(pack.find(id(10))).contains(lateInternal);
                assertThat(pack.find(id(20))).contains(appendedBase);
                assertThat(pack.find(64).orElseThrow().baseId()).contains(id(20));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refusesToFinalizeCyclesWithoutEvidenceOfWhichExternalBaseBreaksThem(boolean memory) throws Exception {
        try (IndexedPack pack = create(memory, directory.resolve("self.index"))) {
            IndexedPack.EntryMetadata self = ref(12, id(1));
            addEntry(pack, self);
            pack.addObject(self.offset(), id(1), GitObjectType.BLOB, 3);
            assertThatThrownBy(() -> pack.finish(12)).isInstanceOf(IOException.class).hasMessageContaining("cycle");
            assertThat(pack.hasUnresolved()).isFalse();
            assertThatThrownBy(() -> pack.finish(12)).isInstanceOf(IOException.class).hasMessageContaining("cycle");
        }
        try (IndexedPack pack = create(memory, directory.resolve("mixed.index"))) {
            IndexedPack.EntryMetadata first = ref(12, id(2));
            IndexedPack.EntryMetadata second = ofs(64, 12);
            addEntry(pack, first);
            pack.addObject(first.offset(), id(1), GitObjectType.BLOB, 3);
            addEntry(pack, second);
            pack.addObject(second.offset(), id(2), GitObjectType.BLOB, 3);
            assertThatThrownBy(() -> pack.finish(12)).isInstanceOf(IOException.class).hasMessageContaining("cycle");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void finalizesDeepForwardChainsWithoutAnInMemoryTraversalStack(boolean memory) throws Exception {
        Path path = directory.resolve("deep.index");
        try (IndexedPack pack = create(memory, path)) {
            for (int i = 1; i <= 2000; i++) {
                IndexedPack.EntryMetadata entry = i == 2000 ? full(12L + 64L * i) : ref(12L + 64L * i, id(i + 1));
                addEntry(pack, entry);
                pack.addObject(entry.offset(), id(i), GitObjectType.BLOB, 3);
            }
            pack.finish(12);
        }
        if (!memory) {
            try (IndexedPack pack = LocalIndexedPack.open(path.resolve("data.pack"), path.resolve("data.mv"))) {
                assertThat(pack.find(id(1))).contains(ref(76, id(2)));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsConflictingCompletionWithoutRemovingTheWaitingDependency(boolean memory) throws Exception {
        try (IndexedPack pack = create(memory, directory.resolve("incoming.index"))) {
            IndexedPack.EntryMetadata base = full(12);
            IndexedPack.EntryMetadata delta = ref(64, id(1));
            addEntry(pack, base);
            pack.addObject(base.offset(), id(1), GitObjectType.BLOB, 3);
            addEntry(pack, delta);
            assertThatThrownBy(() -> pack.addObject(delta.offset(), id(1), GitObjectType.BLOB, 4))
                    .isInstanceOf(IOException.class);
            assertThat(pack.waitingFor(id(1), 12)).contains(delta);
            assertThat(pack.hasUnresolved()).isTrue();
            pack.addObject(delta.offset(), id(2), GitObjectType.BLOB, 4);
            assertThatThrownBy(() -> pack.addObject(delta.offset(), id(3), GitObjectType.BLOB, 4))
                    .isInstanceOf(IOException.class);
            assertThatThrownBy(() -> pack.addObject(delta.offset(), id(2), GitObjectType.TREE, 4))
                    .isInstanceOf(IOException.class);
            assertThatThrownBy(() -> pack.addObject(full(128).offset(), id(4), GitObjectType.BLOB, 3))
                    .isInstanceOf(IOException.class);
            assertThat(pack.find(id(2))).contains(delta);
            assertThat(pack.find(id(3))).isEmpty();
            assertThat(pack.hasUnresolved()).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsMalformedMetadataAndFullObjectCompletionWithDifferentTypeOrSize(boolean memory) throws Exception {
        try (IndexedPack pack = create(memory, directory.resolve("incoming.index"))) {
            for (IndexedPack.EntryMetadata invalid : List.of(new IndexedPack.EntryMetadata(12, 12, 3, GitObjectType.BLOB,
                            OptionalLong.empty(), Optional.empty()),
                    new IndexedPack.EntryMetadata(12, 13, -1, GitObjectType.BLOB,
                            OptionalLong.empty(), Optional.empty()),
                    new IndexedPack.EntryMetadata(12, 13, 3, GitObjectType.BLOB,
                            OptionalLong.empty(), Optional.of(id(1))),
                    new IndexedPack.EntryMetadata(12, 13, 3, GitObjectType.REF_DELTA,
                            OptionalLong.empty(), Optional.empty()), ofs(12, 12))) {
                assertThatThrownBy(() -> addEntry(pack, invalid)).isInstanceOf(IOException.class);
            }
            assertThat(pack.hasUnresolved()).isFalse();
            IndexedPack.EntryMetadata entry = full(12);
            addEntry(pack, entry);
            assertThatThrownBy(() -> addEntry(pack, ref(12, id(1)))).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> pack.addObject(entry.offset(), id(1), GitObjectType.TREE, 3))
                    .isInstanceOf(IOException.class);
            assertThatThrownBy(() -> pack.addObject(entry.offset(), id(1), GitObjectType.BLOB, 4))
                    .isInstanceOf(IOException.class);
            assertThatThrownBy(() -> pack.addObject(entry.offset(), id(1), GitObjectType.REF_DELTA, 3))
                    .isInstanceOf(IOException.class);
            assertThat(pack.hasUnresolved()).isTrue();
            pack.addObject(entry.offset(), id(1), GitObjectType.BLOB, 3);
            assertThat(pack.hasUnresolved()).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsUnresolvedStateAndTracksMutationsAfterSuccessfulCompletion(boolean memory) throws Exception {
        Path path = directory.resolve("incoming.index");
        IndexedPack.EntryMetadata entry = full(12);
        try (IndexedPack pack = create(memory, path)) {
            addEntry(pack, entry);
            assertThatThrownBy(() -> pack.finish(12)).isInstanceOf(IOException.class);
            pack.addObject(entry.offset(), id(1), GitObjectType.BLOB, 3);
            pack.finish(12);
            pack.finish(12);
            addEntry(pack, full(64));
            assertThat(pack.hasUnresolved()).isTrue();
            assertThatThrownBy(() -> pack.finish(12)).isInstanceOf(IOException.class);
            pack.addObject(64, id(2), GitObjectType.BLOB, 3);
            assertThat(pack.hasUnresolved()).isFalse();
            pack.finish(12);
        }
        if (!memory) {
            try (IndexedPack pack = LocalIndexedPack.open(path.resolve("data.pack"), path.resolve("data.mv"))) {
                assertThat(pack.find(id(1))).contains(entry);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tracksDirectMutationsAfterDependencyQueries(boolean memory) throws Exception {
        try (IndexedPack pack = create(memory, directory.resolve("incoming"))) {
            IndexedPack.EntryMetadata base = full(12);
            IndexedPack.EntryMetadata delta = ref(64, id(1));
            addEntry(pack, base);
            pack.addObject(base.offset(), id(1), GitObjectType.BLOB, 3);
            assertThat(pack.hasUnresolved()).isFalse();
            addEntry(pack, delta);
            addEntry(pack, delta);
            assertThat(pack.waitingFor(id(1), 12)).contains(delta);
            assertThat(pack.hasUnresolved()).isTrue();
            pack.addObject(delta.offset(), id(2), GitObjectType.BLOB, 4);
            pack.addObject(delta.offset(), id(2), GitObjectType.BLOB, 4);
            assertThat(pack.waitingFor(id(1), 12)).isEmpty();
            assertThat(pack.hasUnresolved()).isFalse();
            assertThat(pack.nextExternalBase()).isEmpty();
            assertThat(pack.checksumMatches(pack.finish(12))).isTrue();
        }
    }

    private static void addEntry(IndexedPack pack, IndexedPack.EntryMetadata entry) throws IOException {
        pack.addEntry(entry.offset(), entry.dataOffset(), entry.inflatedSize(), entry.type(),
                entry.baseOffset(), entry.baseId());
    }

    private static IndexedPack create(boolean memory, Path path) throws IOException {
        IndexedPack pack = memory ? new InMemoryStorage().newPack() : LocalIndexedPack.create(path);
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
