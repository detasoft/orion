package pro.deta.orion.keymaterial;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class KeyMaterialContentSourceTest {
    @TempDir
    private Path tempDir;

    @Test
    void loadedContentKeepsItsRevisionAndBytesAfterTheSourceChanges() throws Exception {
        InMemoryKeyMaterialContentSource source = new InMemoryKeyMaterialContentSource();
        String first = source.write(bytes("first"), null);
        KeyMaterialContentSource.Content loaded = source.load();
        String second = source.write(bytes("second"), loaded.revision());

        assertThat(loaded.revision()).isEqualTo(first).isNotEqualTo(second);
        assertThat(loaded.bytes()).isEqualTo(bytes("first"));
        KeyMaterialContentSource.Content current = source.load();
        assertThat(current.revision()).isEqualTo(second);
        assertThat(current.bytes()).isEqualTo(bytes("second"));
        current.bytes()[0] = 0;
        assertThat(source.read(second)).isEqualTo(bytes("second"));
    }

    @Test
    void loadsAnAbsentSourceWithoutInventingARevision() throws Exception {
        KeyMaterialContentSource[] sources = {
                new InMemoryKeyMaterialContentSource(),
                new LocalKeyMaterialContentSource(tempDir.resolve("absent.p12"))
        };
        for (KeyMaterialContentSource source : sources) {
            KeyMaterialContentSource.Content loaded = source.load();
            assertThat(loaded.revision()).isNull();
            assertThat(loaded.bytes()).isNull();
        }
    }

    @Test
    void inMemoryStoreVersionsIncrementAndRejectStaleWrites() throws Exception {
        InMemoryKeyMaterialContentSource store = new InMemoryKeyMaterialContentSource();

        String firstVersion = store.write(bytes("first"), null);
        KeyMaterialContentSource.Content first = store.load();
        String firstRevision = first.revision();
        byte[] firstBytes = first.bytes();

        assertThat(firstRevision).isEqualTo(firstVersion);
        assertThat(firstBytes).isEqualTo(bytes("first"));
        assertThat(store.read(firstVersion)).isEqualTo(bytes("first"));
        assertThatThrownBy(() -> store.write(bytes("stale"), "missing"))
                .isInstanceOf(KeyMaterialStoreConflictException.class);

        String secondVersion = store.write(bytes("second"), firstVersion);

        assertThat(secondVersion).isNotEqualTo(firstVersion);
        assertThatThrownBy(() -> store.read(firstVersion)).isInstanceOf(KeyMaterialStoreConflictException.class);
        assertThat(store.read(secondVersion)).isEqualTo(bytes("second"));
        assertThat(store.load().bytes()).isEqualTo(bytes("second"));
    }

    @Test
    void localStoreVersionsTrackContentAndRejectSameSizeStaleWrites() throws Exception {
        Path path = tempDir.resolve(KeyMaterialTestConstants.KEY_STORE_FILE_NAME);
        LocalKeyMaterialContentSource store = new LocalKeyMaterialContentSource(path);

        String firstVersion = store.write(bytes("aaaa"), null);
        KeyMaterialContentSource.Content first = store.load();
        String firstRevision = first.revision();
        assertThat(first.bytes()).isEqualTo(bytes("aaaa"));

        Files.writeString(path, "bbbb", StandardCharsets.UTF_8);
        KeyMaterialContentSource.Content changed = store.load();
        String changedRevision = changed.revision();

        assertThat(firstRevision).isEqualTo(firstVersion);
        assertThat(changedRevision).isNotEqualTo(firstRevision);
        assertThat(changed.bytes()).isEqualTo(bytes("bbbb"));
        assertThat(first.bytes()).isEqualTo(bytes("aaaa"));
        assertThatThrownBy(() -> store.read(firstVersion)).isInstanceOf(KeyMaterialStoreConflictException.class);
        assertThat(store.read(changedRevision)).isEqualTo(bytes("bbbb"));
        assertThatThrownBy(() -> store.write(bytes("cccc"), firstRevision))
                .isInstanceOf(KeyMaterialStoreConflictException.class)
                .hasMessageContaining("changed before save");
    }

    @Test
    void localStoreRejectsStaleWritesAfterAnotherWriterSaves() throws Exception {
        Path path = tempDir.resolve(KeyMaterialTestConstants.KEY_STORE_FILE_NAME);
        LocalKeyMaterialContentSource first = new LocalKeyMaterialContentSource(path);
        LocalKeyMaterialContentSource second = new LocalKeyMaterialContentSource(path);

        String initialVersion = first.write(bytes("first"), null);
        String secondVersion = second.write(bytes("second"), initialVersion);

        assertThatThrownBy(() -> first.write(bytes("stale"), initialVersion))
                .isInstanceOf(KeyMaterialStoreConflictException.class);
        assertThat(second.load().revision()).isEqualTo(secondVersion);
        assertThat(second.load().bytes()).isEqualTo(bytes("second"));
    }

    @Test
    void localStoreSerializesConcurrentWritersAndPreservesCompareAndSwap() throws Exception {
        Path path = tempDir.resolve(KeyMaterialTestConstants.KEY_STORE_FILE_NAME);
        LocalKeyMaterialContentSource first = new LocalKeyMaterialContentSource(path);
        LocalKeyMaterialContentSource second = new LocalKeyMaterialContentSource(path);
        String initialVersion = first.write(bytes("initial"), null);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Throwable> firstResult = executor.submit(
                    () -> concurrentWrite(first, "first", initialVersion, ready, start));
            Future<Throwable> secondResult = executor.submit(
                    () -> concurrentWrite(second, "second", initialVersion, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Throwable> results = Arrays.asList(
                    firstResult.get(10, TimeUnit.SECONDS),
                    secondResult.get(10, TimeUnit.SECONDS));
            int successful = 0;
            int conflicts = 0;
            for (Throwable result : results) {
                if (result == null) {
                    successful++;
                } else if (result instanceof KeyMaterialStoreConflictException) {
                    conflicts++;
                }
            }

            assertThat(successful).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
            assertThat(first.load().bytes()).isIn(bytes("first"), bytes("second"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void localStoreCreatesOwnerOnlyFiles() throws Exception {
        assumeTrue(KeyMaterialFileSecurity.supportsPosix(tempDir), "POSIX permissions are not available");
        Path path = tempDir.resolve(KeyMaterialTestConstants.KEY_STORE_FILE_NAME);
        LocalKeyMaterialContentSource store = new LocalKeyMaterialContentSource(path);

        store.write(bytes("protected"), null);

        assertThat(Files.getPosixFilePermissions(path))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(tempDir.resolve(".orion.p12.lock")))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void localStoreRejectsUnsafeExistingPermissions() throws Exception {
        assumeTrue(KeyMaterialFileSecurity.supportsPosix(tempDir), "POSIX permissions are not available");
        Path path = tempDir.resolve(KeyMaterialTestConstants.KEY_STORE_FILE_NAME);
        Files.write(path, bytes("exposed"));
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
        LocalKeyMaterialContentSource store = new LocalKeyMaterialContentSource(path);

        assertThatThrownBy(store::load)
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("readable only by its owner");
        assertThatThrownBy(() -> store.write(bytes("replacement"), null))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("readable only by its owner");
        assertThat(Files.readAllBytes(path)).isEqualTo(bytes("exposed"));
    }

    @Test
    void localStoreRejectsParentDirectoriesWritableByOtherUsers() throws Exception {
        assumeTrue(KeyMaterialFileSecurity.supportsPosix(tempDir), "POSIX permissions are not available");
        Path exposedDirectory = tempDir.resolve("exposed");
        Files.createDirectory(exposedDirectory);
        Files.setPosixFilePermissions(exposedDirectory, PosixFilePermissions.fromString("rwxrwxrwx"));
        LocalKeyMaterialContentSource store = new LocalKeyMaterialContentSource(
                exposedDirectory.resolve(KeyMaterialTestConstants.KEY_STORE_FILE_NAME));

        assertThatThrownBy(() -> store.write(bytes("protected"), null))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("writable by another user");
        assertThat(Files.exists(store.path())).isFalse();
    }

    @Test
    void localStoreRejectsSymbolicLinkLocations() throws Exception {
        assumeTrue(KeyMaterialFileSecurity.supportsPosix(tempDir), "Symbolic links are not available");
        Path target = tempDir.resolve("actual.p12");
        Files.write(target, bytes("protected"));
        setOwnerOnly(target);
        Path link = tempDir.resolve(KeyMaterialTestConstants.KEY_STORE_FILE_NAME);
        Files.createSymbolicLink(link, target.getFileName());
        LocalKeyMaterialContentSource store = new LocalKeyMaterialContentSource(link);

        assertThatThrownBy(store::load)
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("must not contain symbolic links");
        assertThatThrownBy(() -> store.write(bytes("replacement"), null))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("must not contain symbolic links");
        assertThat(Files.readAllBytes(target)).isEqualTo(bytes("protected"));
    }

    @Test
    void incompletePublicationRequiresExplicitDiscardAndNeverReplacesCurrentStore() throws Exception {
        Path path = tempDir.resolve(KeyMaterialTestConstants.KEY_STORE_FILE_NAME);
        LocalKeyMaterialContentSource store = new LocalKeyMaterialContentSource(path);
        store.write(bytes("published"), null);
        Path incomplete = tempDir.resolve(".orion.p12.interrupted.tmp");
        Files.write(incomplete, bytes("incomplete"));
        setOwnerOnly(incomplete);

        assertThat(store.load().bytes()).isEqualTo(bytes("published"));
        assertThat(Files.exists(incomplete)).isTrue();

        assertThat(store.discardIncompleteWrites()).isEqualTo(1);
        assertThat(Files.exists(incomplete)).isFalse();
        assertThat(store.load().bytes()).isEqualTo(bytes("published"));
        assertThat(store.discardIncompleteWrites()).isZero();
    }

    private static Throwable concurrentWrite(
            LocalKeyMaterialContentSource store,
            String value,
            String expectedVersion,
            CountDownLatch ready,
            CountDownLatch start) {
        ready.countDown();
        try {
            start.await();
            store.write(bytes(value), expectedVersion);
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static void setOwnerOnly(Path path) throws Exception {
        if (KeyMaterialFileSecurity.supportsPosix(path)) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
