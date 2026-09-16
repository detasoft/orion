package pro.deta.orion.git.parser.v2.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.pack.PackObjectParser;
import pro.deta.orion.git.parser.v2.pack.PackUpload;
import pro.deta.orion.git.parser.v2.read.HashedGitObjectRead;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilePackIndexTest {
    @TempDir
    Path directory;

    @Test
    void registersPhysicalEntriesAndCompletesThemWithoutLosingDuplicateObjectOffsets() throws Exception {
        Path path = directory.resolve("incoming.index");
        var first = full(12);
        var duplicate = full(64);
        try (var index = create(path)) {
            assertThat(index.hasUnresolved()).isFalse();
            assertThat(index.find(12)).isEmpty();
            assertThat(index.find(id(1))).isEmpty();
            index.addEntry(first);
            assertThat(index.find(12)).contains(first);
            assertThat(index.find(id(1))).isEmpty();
            assertThat(index.hasUnresolved()).isTrue();
            index.addObject(first, id(1), ObjectType.BLOB, 3);
            index.addEntry(duplicate);
            index.addObject(duplicate, id(1), ObjectType.BLOB, 3);
            index.addObject(first, id(1), ObjectType.BLOB, 3);
            index.addEntry(first);
            assertThat(index.hasUnresolved()).isFalse();
            assertThat(index.find(id(1))).contains(first);
            assertThat(index.find(12)).contains(first);
            assertThat(index.find(64)).contains(duplicate);
            index.finish();
        }
        try (var index = StoredPackIndex.open(path)) {
            assertThat(index.find(12)).contains(first);
            assertThat(index.find(64)).contains(duplicate);
            assertThat(index.find(id(1))).contains(first);
        }
    }

    @Test
    void walksWaitingBranchesAndChainsOneDependentAtATime() throws Exception {
        try (var index = create(directory.resolve("incoming.index"))) {
            var base = full(12);
            var byId = ref(64, id(1));
            var byOffset = ofs(128, 12);
            var child = ref(192, id(2));
            for (var entry : List.of(base, byId, byOffset, child)) {
                index.addEntry(entry);
            }
            index.addObject(base, id(1), ObjectType.BLOB, 3);
            assertThat(index.waitingFor(id(1), 12)).contains(byId);
            assertThat(index.waitingFor(id(1), 12)).contains(byId);
            index.addObject(byId, id(2), ObjectType.BLOB, 99);
            assertThat(index.waitingFor(id(1), 12)).contains(byOffset);
            assertThat(index.waitingFor(id(2), 64)).contains(child);
            index.addObject(byOffset, id(3), ObjectType.BLOB, 100);
            assertThat(index.waitingFor(id(1), 12)).isEmpty();
            assertThat(index.hasUnresolved()).isTrue();
            index.addObject(child, id(4), ObjectType.BLOB, 101);
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
        try (var index = create(path)) {
            for (var entry : List.of(ref(12, id(10)), ref(64, id(20)), ref(128, id(20)))) {
                index.addEntry(entry);
                index.addObject(entry, id((int) entry.offset()), ObjectType.BLOB, 99);
            }
            index.addEntry(lateInternal);
            index.addObject(lateInternal, id(10), ObjectType.BLOB, 3);
            assertThat(index.nextExternalBase()).contains(id(20));
            assertThat(index.nextExternalBase()).contains(id(20));
            assertThatThrownBy(index::finish).isInstanceOf(IOException.class).hasMessageContaining("external");
            index.addEntry(appendedBase);
            index.addObject(appendedBase, id(20), ObjectType.BLOB, 3);
            assertThat(index.nextExternalBase()).isEmpty();
            index.finish();
        }
        try (var index = StoredPackIndex.open(path)) {
            assertThat(index.find(id(10))).contains(lateInternal);
            assertThat(index.find(id(20))).contains(appendedBase);
            assertThat(index.find(64).orElseThrow().baseId()).contains(id(20));
        }
    }

    @Test
    void refusesToFinalizeCyclesWithoutEvidenceOfWhichExternalBaseBreaksThem() throws Exception {
        try (var index = create(directory.resolve("self.index"))) {
            var self = ref(12, id(1));
            index.addEntry(self);
            index.addObject(self, id(1), ObjectType.BLOB, 3);
            assertThatThrownBy(index::finish).isInstanceOf(IOException.class).hasMessageContaining("cycle");
            assertThat(index.hasUnresolved()).isFalse();
            assertThatThrownBy(index::finish).isInstanceOf(IOException.class).hasMessageContaining("cycle");
        }
        try (var index = create(directory.resolve("mixed.index"))) {
            var first = ref(12, id(2));
            var second = ofs(64, 12);
            index.addEntry(first);
            index.addObject(first, id(1), ObjectType.BLOB, 3);
            index.addEntry(second);
            index.addObject(second, id(2), ObjectType.BLOB, 3);
            assertThatThrownBy(index::finish).isInstanceOf(IOException.class).hasMessageContaining("cycle");
        }
    }

    @Test
    void finalizesDeepForwardChainsWithoutAnInMemoryTraversalStack() throws Exception {
        Path path = directory.resolve("deep.index");
        try (var index = create(path)) {
            for (int i = 1; i <= 2000; i++) {
                var entry = i == 2000 ? full(12L + 64L * i) : ref(12L + 64L * i, id(i + 1));
                index.addEntry(entry);
                index.addObject(entry, id(i), ObjectType.BLOB, 3);
            }
            index.finish();
        }
        try (var index = StoredPackIndex.open(path)) {
            assertThat(index.find(id(1))).contains(ref(76, id(2)));
        }
    }

    @Test
    void rejectsConflictingCompletionWithoutRemovingTheWaitingDependency() throws Exception {
        try (var index = create(directory.resolve("incoming.index"))) {
            var base = full(12);
            var delta = ref(64, id(1));
            index.addEntry(base);
            index.addObject(base, id(1), ObjectType.BLOB, 3);
            index.addEntry(delta);
            assertThatThrownBy(() -> index.addObject(delta, id(1), ObjectType.BLOB, 4))
                    .isInstanceOf(IOException.class);
            assertThat(index.waitingFor(id(1), 12)).contains(delta);
            assertThat(index.hasUnresolved()).isTrue();
            index.addObject(delta, id(2), ObjectType.BLOB, 4);
            assertThatThrownBy(() -> index.addObject(delta, id(3), ObjectType.BLOB, 4))
                    .isInstanceOf(IOException.class);
            assertThatThrownBy(() -> index.addObject(delta, id(2), ObjectType.TREE, 4))
                    .isInstanceOf(IOException.class);
            assertThatThrownBy(() -> index.addObject(full(128), id(4), ObjectType.BLOB, 3))
                    .isInstanceOf(IOException.class);
            assertThat(index.find(id(2))).contains(delta);
            assertThat(index.find(id(3))).isEmpty();
            assertThat(index.hasUnresolved()).isFalse();
        }
    }

    @Test
    void rejectsMalformedMetadataAndFullObjectCompletionWithDifferentTypeOrSize() throws Exception {
        try (var index = create(directory.resolve("incoming.index"))) {
            for (var invalid : List.of(new PackObjectParser.Entry(12, 12, 3, ObjectType.BLOB,
                            OptionalLong.empty(), Optional.empty()),
                    new PackObjectParser.Entry(12, 13, -1, ObjectType.BLOB,
                            OptionalLong.empty(), Optional.empty()),
                    new PackObjectParser.Entry(12, 13, 3, ObjectType.BLOB,
                            OptionalLong.empty(), Optional.of(id(1))),
                    new PackObjectParser.Entry(12, 13, 3, ObjectType.REF_DELTA,
                            OptionalLong.empty(), Optional.empty()), ofs(12, 12))) {
                assertThatThrownBy(() -> index.addEntry(invalid)).isInstanceOf(IOException.class);
            }
            assertThat(index.hasUnresolved()).isFalse();
            var entry = full(12);
            index.addEntry(entry);
            assertThatThrownBy(() -> index.addEntry(ref(12, id(1)))).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> index.addObject(entry, id(1), ObjectType.TREE, 3))
                    .isInstanceOf(IOException.class);
            assertThatThrownBy(() -> index.addObject(entry, id(1), ObjectType.BLOB, 4))
                    .isInstanceOf(IOException.class);
            assertThatThrownBy(() -> index.addObject(entry, id(1), ObjectType.REF_DELTA, 3))
                    .isInstanceOf(IOException.class);
            assertThat(index.hasUnresolved()).isTrue();
            index.addObject(entry, id(1), ObjectType.BLOB, 3);
            assertThat(index.hasUnresolved()).isFalse();
        }
    }

    @Test
    void cannotFinalizeUnresolvedStateAndFreezesAllMutationsAfterSuccessfulFinalization() throws Exception {
        Path path = directory.resolve("incoming.index");
        var entry = full(12);
        try (var index = create(path)) {
            index.addEntry(entry);
            assertThatThrownBy(index::finish).isInstanceOf(IOException.class);
            index.addObject(entry, id(1), ObjectType.BLOB, 3);
            index.finish();
            index.finish();
            assertThatThrownBy(() -> index.addEntry(full(64))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> index.addObject(entry, id(1), ObjectType.BLOB, 3))
                    .isInstanceOf(IllegalStateException.class);
        }
        try (var index = StoredPackIndex.open(path)) {
            assertThatThrownBy(() -> index.addEntry(full(64))).isInstanceOf(IllegalStateException.class);
            assertThat(index.find(id(1))).contains(entry);
        }
    }

    @Test
    void closesIdempotentlyWithoutDeletingTheIndex() throws Exception {
        Path path = directory.resolve("incoming.index");
        var index = create(path);
        index.finish();
        index.close();
        index.close();
        assertThatThrownBy(() -> index.find(12)).isInstanceOf(ClosedChannelException.class);
        assertThatThrownBy(() -> index.find(id(1))).isInstanceOf(ClosedChannelException.class);
        assertThatThrownBy(() -> index.waitingFor(id(1), 12)).isInstanceOf(ClosedChannelException.class);
        assertThatThrownBy(index::hasUnresolved).isInstanceOf(ClosedChannelException.class);
        assertThatThrownBy(index::nextExternalBase).isInstanceOf(ClosedChannelException.class);
        assertThatThrownBy(index::finish).isInstanceOf(ClosedChannelException.class);
        assertThat(Files.size(path)).isPositive();
        try (var reopened = StoredPackIndex.open(path)) {
            assertThat(reopened.find(id(1))).isEmpty();
        }
    }

    @Test
    void refusesExistingCreationTargetsAndMissingOrCorruptIndexes() throws Exception {
        Path path = directory.resolve("incoming.index");
        assertThatThrownBy(() -> StoredPackIndex.open(path)).isInstanceOf(IOException.class);
        assertThat(Files.exists(path)).isFalse();
        try (var index = create(path)) {
            index.addEntry(full(12));
            assertThatThrownBy(() -> create(path)).isInstanceOf(FileAlreadyExistsException.class);
            assertThat(index.find(12)).contains(full(12));
        }
        Path corrupt = directory.resolve("corrupt.index");
        Files.write(corrupt, new byte[]{1, 2, 3, 4});
        assertThatThrownBy(() -> StoredPackIndex.open(corrupt)).isInstanceOf(IOException.class);
    }

    @Test
    void persistsIndexedLookupsForManyRecordsAndKeepsDifferentAttemptsIsolated() throws Exception {
        Path first = directory.resolve("first.index");
        Path second = directory.resolve("second.index");
        try (var index = create(first); var other = create(second)) {
            for (int i = 1; i <= 1000; i++) {
                var entry = full(12L + 64L * i);
                index.addEntry(entry);
                index.addObject(entry, id(i), ObjectType.BLOB, 3);
            }
            assertThat(other.find(id(500))).isEmpty();
            index.finish();
            other.finish();
        }
        try (var index = StoredPackIndex.open(first)) {
            for (int i : new int[]{1, 500, 1000}) {
                assertThat(index.find(id(i))).contains(full(12L + 64L * i));
                assertThat(index.find(12L + 64L * i)).contains(full(12L + 64L * i));
            }
            assertThat(index.find(id(1001))).isEmpty();
        }
    }

    @Test
    void suppliesRealFileBackendsToPackUploadWithoutWholePackOrIndexTransfer() throws Exception {
        Path indexPath = directory.resolve("incoming.index");
        Path packPath = directory.resolve("incoming.pack");
        byte[] pack = singleBlobPack();
        ObjectId objectId;
        try (var source = new InputStreamBufferedByteInput(new ByteArrayInputStream(pack));
             var bytes = new FilePackByteStore(packPath);
             var index = create(indexPath)) {
            var upload = new PackUpload(new GitStorageApi(), source, bytes, index);
            var result = upload.next();
            objectId = result.value().orElseThrow();
            assertThat(index.find(objectId)).contains(result.entry());
            assertThat(upload.readObject(12, new HashedGitObjectRead())).isEqualTo(objectId);
            assertThat(upload.hasNext()).isFalse();
            bytes.force();
            index.finish();
        }
        try (var index = StoredPackIndex.open(indexPath)) {
            assertThat(index.find(objectId)).contains(new PackObjectParser.Entry(12, 13, 3, ObjectType.BLOB,
                    OptionalLong.empty(), Optional.empty()));
            assertThat(Files.readAllBytes(packPath)).containsExactly(pack);
        }
    }

    private static FilePackIndex create(Path path) throws IOException {
        return FilePackIndex.create(path, path.resolveSibling(path.getFileName() + ".tmv"));
    }

    private static PackObjectParser.Entry full(long offset) {
        return new PackObjectParser.Entry(offset, offset + 1, 3, ObjectType.BLOB,
                OptionalLong.empty(), Optional.empty());
    }

    private static PackObjectParser.Entry ref(long offset, ObjectId base) {
        return new PackObjectParser.Entry(offset, offset + 21, 3, ObjectType.REF_DELTA,
                OptionalLong.empty(), Optional.of(base));
    }

    private static PackObjectParser.Entry ofs(long offset, long base) {
        return new PackObjectParser.Entry(offset, offset + 2, 3, ObjectType.OFS_DELTA,
                OptionalLong.of(base), Optional.empty());
    }

    private static ObjectId id(int number) {
        return new ObjectId(ByteBuffer.allocate(20).putInt(16, number).array());
    }

    private static byte[] singleBlobPack() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(ByteBuffer.allocate(12).putInt(0x5041434b).putInt(2).putInt(1).array());
        output.write(0x33);
        try (var deflater = new DeflaterOutputStream(output)) {
            deflater.write(new byte[]{1, 2, 3});
        }
        output.writeBytes(MessageDigest.getInstance("SHA-1").digest(output.toByteArray()));
        return output.toByteArray();
    }
}
