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

class KeyMaterialContentStoreTest {
    @TempDir
    private Path tempDir;

    @Test
    void inMemoryStoreVersionsIncrementAndRejectStaleWrites() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();

        String firstVersion = store.write(bytes("first"), null);
        KeyMaterialSnapshot firstSnapshot = store.read().orElseThrow();

        assertThat(firstSnapshot.version()).isEqualTo(firstVersion);
        assertThat(firstSnapshot.bytes()).isEqualTo(bytes("first"));
        assertThatThrownBy(() -> store.write(bytes("stale"), "missing"))
                .isInstanceOf(KeyMaterialStoreConflictException.class);

        String secondVersion = store.write(bytes("second"), firstVersion);

        assertThat(secondVersion).isNotEqualTo(firstVersion);
        assertThat(store.read().orElseThrow().bytes()).isEqualTo(bytes("second"));
    }

    @Test
    void localStoreVersionsTrackContentAndRejectSameSizeStaleWrites() throws Exception {
        Path path = tempDir.resolve(KeyMaterialTestConstants.KEY_STORE_FILE_NAME);
        LocalKeyMaterialContentStore store = new LocalKeyMaterialContentStore(path);

        String firstVersion = store.write(bytes("aaaa"), null);
        KeyMaterialSnapshot firstSnapshot = store.read().orElseThrow();

        Files.writeString(path, "bbbb", StandardCharsets.UTF_8);
        KeyMaterialSnapshot changedSnapshot = store.read().orElseThrow();

        assertThat(firstSnapshot.version()).isEqualTo(firstVersion);
        assertThat(changedSnapshot.version()).isNotEqualTo(firstSnapshot.version());
        assertThat(changedSnapshot.bytes()).isEqualTo(bytes("bbbb"));
        assertThatThrownBy(() -> store.write(bytes("cccc"), firstSnapshot.version()))
                .isInstanceOf(KeyMaterialStoreConflictException.class)
                .hasMessageContaining("changed before save");
    }

    @Test
    void localStoreRejectsStaleWritesAfterAnotherWriterSaves() throws Exception {
        Path path = tempDir.resolve(KeyMaterialTestConstants.KEY_STORE_FILE_NAME);
        LocalKeyMaterialContentStore first = new LocalKeyMaterialContentStore(path);
        LocalKeyMaterialContentStore second = new LocalKeyMaterialContentStore(path);

        String initialVersion = first.write(bytes("first"), null);
        String secondVersion = second.write(bytes("second"), initialVersion);

        assertThatThrownBy(() -> first.write(bytes("stale"), initialVersion))
                .isInstanceOf(KeyMaterialStoreConflictException.class);
        assertThat(second.read().orElseThrow().version()).isEqualTo(secondVersion);
        assertThat(second.read().orElseThrow().bytes()).isEqualTo(bytes("second"));
    }

    @Test
    void localStoreSerializesConcurrentWritersAndPreservesCompareAndSwap() throws Exception {
        Path path = tempDir.resolve(KeyMaterialTestConstants.KEY_STORE_FILE_NAME);
        LocalKeyMaterialContentStore first = new LocalKeyMaterialContentStore(path);
        LocalKeyMaterialContentStore second = new LocalKeyMaterialContentStore(path);
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
            assertThat(first.read().orElseThrow().bytes()).isIn(bytes("first"), bytes("second"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void localStoreCreatesOwnerOnlyFiles() throws Exception {
        assumeTrue(KeyMaterialFileSecurity.supportsPosix(tempDir), "POSIX permissions are not available");
        Path path = tempDir.resolve(KeyMaterialTestConstants.KEY_STORE_FILE_NAME);
        LocalKeyMaterialContentStore store = new LocalKeyMaterialContentStore(path);

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
        LocalKeyMaterialContentStore store = new LocalKeyMaterialContentStore(path);

        assertThatThrownBy(store::read)
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
        LocalKeyMaterialContentStore store = new LocalKeyMaterialContentStore(
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
        LocalKeyMaterialContentStore store = new LocalKeyMaterialContentStore(link);

        assertThatThrownBy(store::read)
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
        LocalKeyMaterialContentStore store = new LocalKeyMaterialContentStore(path);
        store.write(bytes("published"), null);
        Path incomplete = tempDir.resolve(".orion.p12.interrupted.tmp");
        Files.write(incomplete, bytes("incomplete"));
        setOwnerOnly(incomplete);

        assertThat(store.read().orElseThrow().bytes()).isEqualTo(bytes("published"));
        assertThat(Files.exists(incomplete)).isTrue();

        assertThat(store.discardIncompleteWrites()).isEqualTo(1);
        assertThat(Files.exists(incomplete)).isFalse();
        assertThat(store.read().orElseThrow().bytes()).isEqualTo(bytes("published"));
        assertThat(store.discardIncompleteWrites()).isZero();
    }

    private static Throwable concurrentWrite(
            LocalKeyMaterialContentStore store,
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
