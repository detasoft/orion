package pro.deta.orion.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BlobStorageTest {
    @TempDir
    private Path tempDir;

    @Test
    void loadedContentKeepsItsRevisionAndBytesAfterTheSourceChanges() throws Exception {
        InMemoryBlobStorage source = new InMemoryBlobStorage();
        String first = source.write(new BlobStorage.Modification(null, bytes("first")));
        BlobStorage.Content loaded = source.load();
        String second = source.write(new BlobStorage.Modification(loaded.revision(), bytes("second")));

        assertThat(loaded.revision()).isEqualTo(first).isNotEqualTo(second);
        assertThat(loaded.bytes()).isEqualTo(bytes("first"));
        BlobStorage.Content current = source.load();
        assertThat(current.revision()).isEqualTo(second);
        assertThat(current.bytes()).isEqualTo(bytes("second"));
        current.bytes()[0] = 0;
        assertThat(source.read(second)).isEqualTo(bytes("second"));
    }

    @Test
    void loadsAnAbsentSourceWithoutInventingARevision() throws Exception {
        BlobStorage[] sources = {
                new InMemoryBlobStorage(),
                new LocalBlobStorage(tempDir.resolve("absent.p12"))
        };
        for (BlobStorage source : sources) {
            BlobStorage.Content loaded = source.load();
            assertThat(loaded.revision()).isNull();
            assertThat(loaded.bytes()).isNull();
        }
    }

    @Test
    void inMemoryStoreVersionsIncrementAndRejectStaleWrites() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();

        String firstVersion = store.write(new BlobStorage.Modification(null, bytes("first")));
        BlobStorage.Content first = store.load();
        String firstRevision = first.revision();
        byte[] firstBytes = first.bytes();

        assertThat(firstRevision).isEqualTo(firstVersion);
        assertThat(firstBytes).isEqualTo(bytes("first"));
        assertThat(store.read(firstVersion)).isEqualTo(bytes("first"));
        assertThatThrownBy(() -> store.write(new BlobStorage.Modification("missing", bytes("stale"))))
                .isInstanceOf(BlobStorageConflictException.class);

        String secondVersion = store.write(new BlobStorage.Modification(firstVersion, bytes("second")));

        assertThat(secondVersion).isNotEqualTo(firstVersion);
        assertThatThrownBy(() -> store.read(firstVersion)).isInstanceOf(BlobStorageConflictException.class);
        assertThat(store.read(secondVersion)).isEqualTo(bytes("second"));
        assertThat(store.load().bytes()).isEqualTo(bytes("second"));
    }

    @Test
    void localStoreVersionsTrackContentAndRejectSameSizeStaleWrites() throws Exception {
        Path path = tempDir.resolve("orion.p12");
        LocalBlobStorage store = new LocalBlobStorage(path);

        String firstVersion = store.write(new BlobStorage.Modification(null, bytes("aaaa")));
        BlobStorage.Content first = store.load();
        String firstRevision = first.revision();
        assertThat(first.bytes()).isEqualTo(bytes("aaaa"));

        Files.writeString(path, "bbbb", StandardCharsets.UTF_8);
        BlobStorage.Content changed = store.load();
        String changedRevision = changed.revision();

        assertThat(firstRevision).isEqualTo(firstVersion);
        assertThat(changedRevision).isNotEqualTo(firstRevision);
        assertThat(changed.bytes()).isEqualTo(bytes("bbbb"));
        assertThat(first.bytes()).isEqualTo(bytes("aaaa"));
        assertThatThrownBy(() -> store.read(firstVersion)).isInstanceOf(BlobStorageConflictException.class);
        assertThat(store.read(changedRevision)).isEqualTo(bytes("bbbb"));
        assertThatThrownBy(() -> store.write(new BlobStorage.Modification(firstRevision, bytes("cccc"))))
                .isInstanceOf(BlobStorageConflictException.class)
                .hasMessageContaining("changed before save");
    }

    @Test
    void localStoreCreatesOwnerOnlyFiles() throws Exception {
        assumeTrue(ProtectedFiles.supportsPosix(tempDir), "POSIX permissions are not available");
        Path path = tempDir.resolve("orion.p12");
        LocalBlobStorage store = new LocalBlobStorage(path);

        store.write(new BlobStorage.Modification(null, bytes("protected")));

        assertThat(Files.getPosixFilePermissions(path))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void localStoreRejectsUnsafeExistingPermissions() throws Exception {
        assumeTrue(ProtectedFiles.supportsPosix(tempDir), "POSIX permissions are not available");
        Path path = tempDir.resolve("orion.p12");
        Files.write(path, bytes("exposed"));
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
        LocalBlobStorage store = new LocalBlobStorage(path);

        assertThatThrownBy(store::load)
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("readable only by its owner");
        assertThatThrownBy(() -> store.write(new BlobStorage.Modification(null, bytes("replacement"))))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("readable only by its owner");
        assertThat(Files.readAllBytes(path)).isEqualTo(bytes("exposed"));
    }

    @Test
    void localStoreRejectsParentDirectoriesWritableByOtherUsers() throws Exception {
        assumeTrue(ProtectedFiles.supportsPosix(tempDir), "POSIX permissions are not available");
        Path exposedDirectory = tempDir.resolve("exposed");
        Files.createDirectory(exposedDirectory);
        Files.setPosixFilePermissions(exposedDirectory, PosixFilePermissions.fromString("rwxrwxrwx"));
        Path path = exposedDirectory.resolve("orion.p12");
        LocalBlobStorage store = new LocalBlobStorage(path);

        assertThatThrownBy(() -> store.write(new BlobStorage.Modification(null, bytes("protected"))))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("writable by another user");
        assertThat(Files.exists(path)).isFalse();
    }

    @Test
    void localStoreRejectsSymbolicLinkLocations() throws Exception {
        assumeTrue(ProtectedFiles.supportsPosix(tempDir), "Symbolic links are not available");
        Path target = tempDir.resolve("actual.p12");
        Files.write(target, bytes("protected"));
        setOwnerOnly(target);
        Path link = tempDir.resolve("orion.p12");
        Files.createSymbolicLink(link, target.getFileName());
        LocalBlobStorage store = new LocalBlobStorage(link);

        assertThatThrownBy(store::load)
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("must not contain symbolic links");
        assertThatThrownBy(() -> store.write(new BlobStorage.Modification(null, bytes("replacement"))))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("must not contain symbolic links");
        assertThat(Files.readAllBytes(target)).isEqualTo(bytes("protected"));
    }

    private static void setOwnerOnly(Path path) throws Exception {
        if (ProtectedFiles.supportsPosix(path)) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
