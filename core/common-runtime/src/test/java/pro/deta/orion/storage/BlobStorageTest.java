package pro.deta.orion.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.event.OrionEvents;
import pro.deta.orion.event.type.OrionEvent;
import pro.deta.orion.storage.BlobStorage.BlobStorageOperations;
import pro.deta.orion.util.VcChangeDescription;
import pro.deta.orion.util.VcModificationException;
import pro.deta.orion.util.VcResourceModified;
import pro.deta.orion.util.VcRevision;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BlobStorageTest {
    @TempDir
    private Path tempDir;
    private final OrionEventManager events = new OrionEventManager();

    @BeforeEach
    void startEvents() {
        events.onStart();
    }

    @AfterEach
    void stopEvents() {
        events.onStop();
    }

    @Test
    void loadsAnAbsentSourceWithoutInventingARevision() {
        for (BlobStorage store : List.of(new InMemoryBlobStorage(events),
                new LocalBlobStorage(tempDir.resolve("missing"), events))) {
            try (BlobStorageOperations operation = store.open(new VcRevision.Latest())) {
                assertThat(operation.revision()).isInstanceOf(VcRevision.Absent.class);
                assertThatThrownBy(operation::readContent).isInstanceOf(UncheckedIOException.class)
                        .hasCauseInstanceOf(java.io.FileNotFoundException.class);
            }
        }
    }

    @Test
    void localStoreCreatesOwnerOnlyFiles() throws Exception {
        assumeTrue(ProtectedFiles.supportsPosix(tempDir), "POSIX permissions are not available");
        Path path = tempDir.resolve("created");
        LocalBlobStorage store = new LocalBlobStorage(path, events);
        try (BlobStorageOperations operation = store.open(new VcRevision.Latest())) {
            operation.write(bytes("protected"));
            VcRevision.Stored saved = operation.commit(VcChangeDescription.EMPTY);
            assertThat(store.readBytes(saved)).isEqualTo(bytes("protected"));
        }
        assertThat(Files.getPosixFilePermissions(path)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void onlyOneCreatorCanPublishFromTheAbsentSnapshot() {
        for (BlobStorage store : List.of(new InMemoryBlobStorage(events),
                new LocalBlobStorage(tempDir.resolve("first.p12"), events))) {
            List<VcResourceModified> changes = new CopyOnWriteArrayList<>();
            OrionEvents subscription = events.forTarget(new Object());
            subscription.subscribe(VcResourceModified.class, store, changes::add);
            try (BlobStorageOperations first = store.open(new VcRevision.Absent());
                    BlobStorageOperations stale = store.open(new VcRevision.Latest())) {
                first.write(bytes("first"));
                stale.write(bytes("stale"));
                VcRevision.Stored saved = first.commit(VcChangeDescription.EMPTY);
                assertThatThrownBy(() -> stale.commit(VcChangeDescription.EMPTY))
                        .isInstanceOfSatisfying(VcModificationException.class, failure -> {
                            assertThat(failure.expectedRevision()).isEqualTo(new VcRevision.Absent());
                            assertThat(failure.actualRevision()).isEqualTo(saved);
                        });
                assertThat(store.readBytes(saved)).isEqualTo(bytes("first"));
                assertThatThrownBy(() -> store.open(new VcRevision.Absent()))
                        .isInstanceOf(VcModificationException.class);
                events.publishAndWait(new Barrier());
                assertThat(changes).hasSize(1);
                assertThat(changes.getFirst().oldRevision()).isEqualTo(new VcRevision.Absent());
                assertThat(changes.getFirst().revision()).isEqualTo(saved);
            } finally {
                subscription.unsubscribe(VcResourceModified.class, store);
            }
        }
    }

    @Test
    void openedStateKeepsItsRevisionAndBytesAfterTheSourceChanges() {
        InMemoryBlobStorage source = new InMemoryBlobStorage(bytes("first"), false, events);
        try (BlobStorageOperations snapshot = source.open(new VcRevision.Latest());
             BlobStorageOperations update = source.open(new VcRevision.Latest())) {
            VcRevision.Stored base = assertInstanceOf(VcRevision.Stored.class, snapshot.revision());
            update.write(bytes("second"));
            VcRevision.Stored saved = update.commit(VcChangeDescription.EMPTY);

            assertThat(snapshot.revision()).isEqualTo(base).isNotEqualTo(saved);
            assertThat(snapshot.readContent()).isEqualTo(bytes("first"));
            byte[] returned = source.readBytes(saved);
            returned[0] = 0;
            assertThat(source.readBytes(saved)).isEqualTo(bytes("second"));
            assertThat(source.readBytes(base)).isEqualTo(bytes("first"));
        }
    }

    @Test
    void absentSourceCannotOpenAStoredVersion() {
        InMemoryBlobStorage memory = new InMemoryBlobStorage(events);
        LocalBlobStorage local = new LocalBlobStorage(tempDir.resolve("absent.p12"), events);
        assertThatThrownBy(() -> memory.open(new VcRevision.Stored("missing"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> local.open(new VcRevision.Stored("missing"))).isInstanceOf(UncheckedIOException.class);
        assertThat(Files.exists(tempDir.resolve("absent.p12"))).isFalse();
    }

    @Test
    void commitsOnlyTheFinalPreparedBytesAndCopiesCallerInput() {
        InMemoryBlobStorage store = new InMemoryBlobStorage(bytes("initial"), false, events);
        try (BlobStorageOperations operation = store.open(new VcRevision.Latest())) {
            operation.write(bytes("first replacement"));
            byte[] replacement = bytes("second replacement");
            operation.write(replacement);
            replacement[0] = 0;
            assertThat(operation.readContent()).isEqualTo(bytes("second replacement"));
            assertThat(store.readBytes(new VcRevision.Latest())).isEqualTo(bytes("initial"));
            VcRevision.Stored saved = operation.commit(VcChangeDescription.EMPTY);
            assertThat(saved).isEqualTo(new VcRevision.Stored("2"));
            assertThat(store.readBytes(saved)).isEqualTo(bytes("second replacement"));
        }
    }

    @Test
    void rejectsStaleWritesWithoutDiscardingHistoricalReads() {
        InMemoryBlobStorage store = new InMemoryBlobStorage(bytes("initial"), false, events);
        try (BlobStorageOperations first = store.open(new VcRevision.Latest());
             BlobStorageOperations stale = store.open(new VcRevision.Latest())) {
            stale.write(bytes("stale"));
            first.write(bytes("first"));
            VcRevision.Stored saved = first.commit(VcChangeDescription.EMPTY);
            assertThatThrownBy(() -> stale.commit(VcChangeDescription.EMPTY))
                    .isInstanceOfSatisfying(VcModificationException.class, failure -> {
                        assertThat(failure.expectedRevision()).isEqualTo(stale.revision());
                        assertThat(failure.actualRevision()).isEqualTo(saved);
                    });
            assertThat(store.readBytes(assertInstanceOf(VcRevision.Stored.class, stale.revision())))
                    .isEqualTo(bytes("initial"));
            assertThat(store.readBytes(saved)).isEqualTo(bytes("first"));
        }
        assertThatThrownBy(() -> store.open(new VcRevision.Stored("missing")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closingWithoutCommitLeavesBothStoresUnchanged() throws Exception {
        Path path = seed("initial");
        for (BlobStorage store : List.of(new InMemoryBlobStorage(bytes("initial"), false, events),
                new LocalBlobStorage(path, events))) {
            VcRevision base;
            try (BlobStorageOperations operation = store.open(new VcRevision.Latest())) {
                base = operation.revision();
                operation.write(bytes("uncommitted"));
            }
            try (BlobStorageOperations actual = store.open(new VcRevision.Latest())) {
                assertThat(actual.revision()).isEqualTo(base);
                assertThat(actual.readContent()).isEqualTo(bytes("initial"));
            }
        }
    }

    @Test
    void readOnlyMemoryAllowsReadingButRejectsPreparation() {
        InMemoryBlobStorage store = new InMemoryBlobStorage(bytes("initial"), true, events);
        try (BlobStorageOperations operation = store.open(new VcRevision.Latest())) {
            assertThat(operation.readContent()).isEqualTo(bytes("initial"));
            assertThatThrownBy(() -> operation.write(bytes("replacement")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("read-only");
        }
    }

    @Test
    void publishesOnlyCommittedChangesAndSupportsUnsubscribe() throws Exception {
        for (BlobStorage store : List.of(new InMemoryBlobStorage(bytes("initial"), false, events),
                new LocalBlobStorage(seed("initial"), events))) {
            List<VcResourceModified> changes = new CopyOnWriteArrayList<>();
            OrionEvents subscription = events.forTarget(new Object());
            subscription.subscribe(VcResourceModified.class, store, changes::add);
            try {
                try (BlobStorageOperations operation = store.open(new VcRevision.Latest())) {
                    assertThat(operation.commit(VcChangeDescription.EMPTY)).isEqualTo(operation.revision());
                    events.publishAndWait(new Barrier());
                    assertThat(changes).isEmpty();
                    operation.write(bytes("saved"));
                    VcRevision.Stored saved = operation.commit(VcChangeDescription.EMPTY);
                    events.publishAndWait(new Barrier());
                    assertThat(changes).hasSize(1);
                    assertThat(changes.getFirst().source()).isSameAs(store);
                    assertThat(changes.getFirst().oldRevision()).isEqualTo(operation.revision());
                    assertThat(changes.getFirst().revision()).isEqualTo(saved);
                }
                subscription.unsubscribe(VcResourceModified.class, store);
                try (BlobStorageOperations operation = store.open(new VcRevision.Latest())) {
                    operation.write(bytes("after unsubscribe"));
                    operation.commit(VcChangeDescription.EMPTY);
                }
                events.publishAndWait(new Barrier());
                assertThat(changes).hasSize(1);
            } finally {
                subscription.unsubscribe(VcResourceModified.class, store);
            }
        }
    }

    @Test
    void localSnapshotSurvivesExternalChangesButCannotOverwriteThem() throws Exception {
        Path path = seed("aaaa");
        LocalBlobStorage store = new LocalBlobStorage(path, events);
        try (BlobStorageOperations snapshot = store.open(new VcRevision.Latest())) {
            VcRevision.Stored base = assertInstanceOf(VcRevision.Stored.class, snapshot.revision());
            Files.writeString(path, "bbbb", StandardCharsets.UTF_8);
            assertThat(snapshot.readContent()).isEqualTo(bytes("aaaa"));
            assertThat(store.readBytes(new VcRevision.Latest())).isEqualTo(bytes("bbbb"));
            assertThatThrownBy(() -> store.open(base)).isInstanceOf(IllegalArgumentException.class);
            snapshot.write(bytes("cccc"));
            assertThatThrownBy(() -> snapshot.commit(VcChangeDescription.EMPTY))
                    .isInstanceOf(VcModificationException.class);
            assertThat(Files.readString(path)).isEqualTo("bbbb");
        }
    }

    @Test
    void localCommitPublishesOwnerOnlyFiles() throws Exception {
        assumeTrue(ProtectedFiles.supportsPosix(tempDir), "POSIX permissions are not available");
        Path path = seed("initial");
        LocalBlobStorage store = new LocalBlobStorage(path, events);
        try (BlobStorageOperations operation = store.open(new VcRevision.Latest())) {
            operation.write(bytes("protected"));
            VcRevision.Stored saved = operation.commit(VcChangeDescription.EMPTY);
            assertThat(store.readBytes(saved)).isEqualTo(bytes("protected"));
        }
        assertThat(Files.getPosixFilePermissions(path)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void localStoreRejectsUnsafeExistingPermissions() throws Exception {
        assumeTrue(ProtectedFiles.supportsPosix(tempDir), "POSIX permissions are not available");
        Path path = seed("exposed");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
        LocalBlobStorage store = new LocalBlobStorage(path, events);
        assertThatThrownBy(() -> store.open(new VcRevision.Latest()))
                .isInstanceOf(UncheckedIOException.class)
                .hasRootCauseMessage("Blob location must be readable only by its owner: " + path.toRealPath());
        assertThat(Files.readString(path)).isEqualTo("exposed");
    }

    @Test
    void localStoreRejectsParentDirectoriesWritableByOtherUsers() throws Exception {
        assumeTrue(ProtectedFiles.supportsPosix(tempDir), "POSIX permissions are not available");
        Path directory = Files.createDirectory(tempDir.resolve("exposed"));
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxrwx"));
        Path path = directory.resolve("orion.p12");
        LocalBlobStorage store = new LocalBlobStorage(path, events);
        assertThatThrownBy(() -> store.open(new VcRevision.Latest()))
                .isInstanceOf(UncheckedIOException.class).hasStackTraceContaining("writable by another user");
        assertThat(Files.exists(path)).isFalse();
    }

    @Test
    void localStoreRejectsSymbolicLinkLocations() throws Exception {
        assumeTrue(ProtectedFiles.supportsPosix(tempDir), "Symbolic links are not available");
        Path target = seed("protected");
        Path link = tempDir.resolve("link.p12");
        Files.createSymbolicLink(link, target.getFileName());
        LocalBlobStorage store = new LocalBlobStorage(link, events);
        assertThatThrownBy(() -> store.open(new VcRevision.Latest()))
                .isInstanceOf(UncheckedIOException.class).hasStackTraceContaining("must not contain symbolic links");
        assertThat(Files.readString(target)).isEqualTo("protected");
    }

    private Path seed(String content) throws Exception {
        Path path = Files.createTempFile(tempDir, "blob-", ".p12");
        Files.writeString(path, content);
        if (ProtectedFiles.supportsPosix(path)) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
        return path;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class Barrier extends OrionEvent {}
}
