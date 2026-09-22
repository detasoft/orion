package pro.deta.orion.acl.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.BootstrapRepositorySources;
import pro.deta.orion.git.proxy.ResolvedBootstrapSource;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.schema.config.BootstrapConfigurationSourceConfig;
import pro.deta.orion.util.Result;

import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class LocalAccessControlStorageTest {
    private static final String ACL_PATH = "config/orion.xml";
    private static final String ROLES_PATH = "roles/custom.xml";

    @TempDir
    private Path root;

    @Test
    void loadsExistingConfiguredFile() throws Exception {
        Files.createDirectories(root.resolve("config"));
        Files.write(root.resolve(ACL_PATH), bytes("existing ACL"));
        LocalAccessControlStorage storage = new LocalAccessControlStorage(config(root));

        AccessControlSnapshot snapshot = storage.load().valueOrFailure("existing ACL");

        assertThat(snapshot.files()).containsOnlyKeys(ACL_PATH)
                .containsEntry(ACL_PATH, bytes("existing ACL"));
        assertThat(snapshot.version()).isPresent();
    }

    @Test
    void reportsMissingConfiguredFile() {
        LocalAccessControlStorage storage = new LocalAccessControlStorage(config(root));

        Result<AccessControlSnapshot> result = storage.load();

        assertThat(result).isInstanceOf(Result.Failure.class);
        assertThat(((Result.Failure<?>) result).code()).isEqualTo(Result.FailureCode.NOT_FOUND);
    }

    @Test
    void reportsMissingRootWithoutCreatingIt() {
        Path missing = root.resolve("missing");
        Result<AccessControlSnapshot> result = new LocalAccessControlStorage(config(missing)).load();
        assertThat(result).isInstanceOf(Result.Failure.class);
        assertThat(((Result.Failure<?>) result).code()).isEqualTo(Result.FailureCode.NOT_FOUND);
        assertThat(missing).doesNotExist();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void readsWithAnExistingReadOnlyLockInAReadOnlyDirectory() throws Exception {
        LocalAccessControlStorage storage = new LocalAccessControlStorage(config(root));
        storage.save(AccessControlSnapshot.singleFile(ACL_PATH, bytes("ACL")),
                new AccessControlSaveRequest("seed", UserEmail.EMPTY));
        Path lock = root.resolve(".orion-configuration.lock");
        Set<PosixFilePermission> directoryPermissions = Files.getPosixFilePermissions(root);
        Set<PosixFilePermission> lockPermissions = Files.getPosixFilePermissions(lock);
        try {
            Files.setPosixFilePermissions(lock, PosixFilePermissions.fromString("r--r--r--"));
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("r-xr-xr-x"));
            assumeFalse(Files.isWritable(root), "requires filesystem permissions to deny directory writes");
            assertThat(storage.load().valueOrFailure("read-only ACL").files().get(ACL_PATH)).isEqualTo(bytes("ACL"));
        } finally {
            Files.setPosixFilePermissions(root, directoryPermissions);
            Files.setPosixFilePermissions(lock, lockPermissions);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void reportsLockCreationFailureInsteadOfReadingWithoutALock() throws Exception {
        Files.createDirectories(root.resolve("config"));
        Files.write(root.resolve(ACL_PATH), bytes("ACL"));
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(root);
        try {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("r-xr-xr-x"));
            assumeFalse(Files.isWritable(root), "requires filesystem permissions to deny directory writes");
            Result<AccessControlSnapshot> result = new LocalAccessControlStorage(config(root)).load();
            assertThat(result).isInstanceOf(Result.Failure.class);
            assertThat(((Result.Failure<?>) result).code()).isEqualTo(Result.FailureCode.GENERAL);
        } finally {
            Files.setPosixFilePermissions(root, permissions);
        }
    }

    @Test
    void readerInAnotherProcessWaitsForTheWholeSave() throws Exception {
        Files.createDirectories(root.resolve("config"));
        Files.createDirectories(root.resolve("roles"));
        Files.write(root.resolve(ACL_PATH), bytes("old ACL"));
        Files.write(root.resolve(ROLES_PATH), bytes("old roles"));
        Path lockPath = root.resolve(".orion-configuration.lock");
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(lockPath,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            java.nio.channels.FileLock lock = channel.lock();
            Process reader = null;
            java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                Files.write(root.resolve(ACL_PATH), bytes("new ACL"));
                reader = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", System.getProperty("java.class.path"), LockedReader.class.getName(), root.toString())
                        .redirectError(ProcessBuilder.Redirect.INHERIT).start();
                java.io.BufferedReader output = reader.inputReader(StandardCharsets.UTF_8);
                assertThat(executor.submit(output::readLine).get(10, java.util.concurrent.TimeUnit.SECONDS))
                        .isEqualTo("ready");
                java.util.concurrent.Future<String> loaded = executor.submit(output::readLine);
                assertThatThrownBy(() -> loaded.get(500, java.util.concurrent.TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                Files.write(root.resolve(ROLES_PATH), bytes("new roles"));
                lock.release();
                assertThat(loaded.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("new ACL|new roles");
                assertThat(reader.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(reader.exitValue()).isZero();
            } finally {
                if (lock.isValid()) {
                    lock.release();
                }
                if (reader != null) {
                    reader.destroyForcibly();
                    reader.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                }
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    public static class LockedReader {
        public static void main(String[] args) {
            BootstrapConfigurationSourceConfig configuration = config(Path.of(args[0]));
            configuration.setPaths(List.of(ACL_PATH, ROLES_PATH));
            LocalAccessControlStorage storage = new LocalAccessControlStorage(configuration);
            System.out.println("ready");
            AccessControlSnapshot snapshot = storage.load().valueOrFailure("locked read");
            System.out.println(new String(snapshot.files().get(ACL_PATH), StandardCharsets.UTF_8)
                    + "|" + new String(snapshot.files().get(ROLES_PATH), StandardCharsets.UTF_8));
        }
    }

    @Test
    void initialSaveCreatesRootAndParentDirectories() throws Exception {
        Path directory = root.resolve("new-acl");
        LocalAccessControlStorage storage = new LocalAccessControlStorage(config(directory));

        storage.save(
                AccessControlSnapshot.singleFile(ACL_PATH, bytes("initial ACL")),
                new AccessControlSaveRequest("initial ACL", UserEmail.EMPTY));

        assertThat(Files.readAllBytes(directory.resolve(ACL_PATH))).isEqualTo(bytes("initial ACL"));
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            Path ordinary = Files.createFile(root.resolve("ordinary.xml"));
            assertThat(Files.getPosixFilePermissions(directory.resolve(ACL_PATH)))
                    .isEqualTo(Files.getPosixFilePermissions(ordinary));
        }
        assertThat(storage.load().valueOrFailure("initial ACL").files())
                .containsOnlyKeys(ACL_PATH)
                .containsEntry(ACL_PATH, bytes("initial ACL"));
    }

    @Test
    void overwritesExistingFileWithShorterContent() throws Exception {
        Files.createDirectories(root.resolve("config"));
        Files.write(root.resolve(ACL_PATH), bytes("previous longer ACL content"));
        LocalAccessControlStorage storage = new LocalAccessControlStorage(config(root));

        storage.save(
                AccessControlSnapshot.singleFile(ACL_PATH, bytes("updated ACL")),
                new AccessControlSaveRequest("update ACL", UserEmail.EMPTY));

        assertThat(Files.readAllBytes(root.resolve(ACL_PATH))).isEqualTo(bytes("updated ACL"));
        LocalAccessControlStorage reopened = new LocalAccessControlStorage(config(root));
        assertThat(reopened.load().valueOrFailure("updated ACL").files())
                .containsOnlyKeys(ACL_PATH)
                .containsEntry(ACL_PATH, bytes("updated ACL"));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void publishesACompleteReplacementAndPreservesExistingPermissions() throws Exception {
        LocalAccessControlStorage storage = new LocalAccessControlStorage(config(root));
        AccessControlSaveRequest request = new AccessControlSaveRequest("save", UserEmail.EMPTY);
        storage.save(AccessControlSnapshot.singleFile(ACL_PATH, bytes("previous complete document")), request);
        Path file = root.resolve(ACL_PATH);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
        PosixFileAttributes before = Files.readAttributes(file, PosixFileAttributes.class);
        try (InputStream previous = Files.newInputStream(file)) {
            storage.save(AccessControlSnapshot.singleFile(ACL_PATH, bytes("replacement")), request);
            assertThat(previous.readAllBytes()).isEqualTo(bytes("previous complete document"));
            assertThat(Files.readAllBytes(file)).isEqualTo(bytes("replacement"));
        }
        PosixFileAttributes after = Files.readAttributes(file, PosixFileAttributes.class);
        assertThat(after.permissions()).isEqualTo(before.permissions());
        assertThat(after.owner()).isEqualTo(before.owner());
        assertThat(after.group()).isEqualTo(before.group());
        try (java.util.stream.Stream<Path> children = Files.list(file.getParent())) {
            assertThat(children.toList()).containsExactly(file);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void leavesTheOldDocumentIntactWhenReplacementCannotBePrepared() throws Exception {
        LocalAccessControlStorage storage = new LocalAccessControlStorage(config(root));
        AccessControlSaveRequest request = new AccessControlSaveRequest("save", UserEmail.EMPTY);
        storage.save(AccessControlSnapshot.singleFile(ACL_PATH, bytes("previous")), request);
        Path file = root.resolve(ACL_PATH);
        Path parent = file.getParent();
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(parent);
        try {
            Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("r-xr-xr-x"));
            assumeFalse(Files.isWritable(parent), "requires filesystem permissions to deny directory writes");
            assertThatThrownBy(() -> storage.save(
                    AccessControlSnapshot.singleFile(ACL_PATH, bytes("replacement")), request))
                    .isInstanceOf(RuntimeException.class).hasCauseInstanceOf(java.io.IOException.class);
            assertThat(Files.readAllBytes(file)).isEqualTo(bytes("previous"));
        } finally {
            Files.setPosixFilePermissions(parent, permissions);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void refusesToReplaceAReadOnlyDocumentWithoutLeavingArtifacts() throws Exception {
        LocalAccessControlStorage storage = new LocalAccessControlStorage(config(root));
        AccessControlSaveRequest request = new AccessControlSaveRequest("save", UserEmail.EMPTY);
        storage.save(AccessControlSnapshot.singleFile(ACL_PATH, bytes("previous")), request);
        Path file = root.resolve(ACL_PATH);
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
            assumeFalse(Files.isWritable(file), "requires filesystem permissions to deny writes");
            assertThatThrownBy(() -> storage.save(
                    AccessControlSnapshot.singleFile(ACL_PATH, bytes("replacement")), request))
                    .isInstanceOf(RuntimeException.class).hasCauseInstanceOf(java.io.IOException.class);
            assertThat(Files.readAllBytes(file)).isEqualTo(bytes("previous"));
            try (java.util.stream.Stream<Path> children = Files.list(file.getParent())) {
                assertThat(children.toList()).containsExactly(file);
            }
        } finally {
            Files.setPosixFilePermissions(file, permissions);
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void preparesEveryDocumentBeforePublishingAny(boolean firstExists) throws Exception {
        Files.createDirectories(root.resolve("config"));
        Files.createDirectories(root.resolve("roles"));
        Path first = root.resolve(ACL_PATH);
        Path second = root.resolve(ROLES_PATH);
        if (firstExists) {
            Files.write(first, bytes("old ACL"));
        }
        Files.write(second, bytes("old roles"));
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(second.getParent());
        Map<String, byte[]> changed = new LinkedHashMap<>();
        changed.put(ACL_PATH, bytes("new ACL"));
        changed.put(ROLES_PATH, bytes("new roles"));
        LocalAccessControlStorage storage = new LocalAccessControlStorage(config(root));
        try {
            Files.setPosixFilePermissions(second.getParent(), PosixFilePermissions.fromString("r-xr-xr-x"));
            assumeFalse(Files.isWritable(second.getParent()), "requires permissions to deny directory writes");
            assertThatThrownBy(() -> storage.save(new AccessControlSnapshot(changed, Optional.empty()),
                    new AccessControlSaveRequest("save both", UserEmail.EMPTY)))
                    .isInstanceOf(RuntimeException.class).hasCauseInstanceOf(java.io.IOException.class);
            if (firstExists) {
                assertThat(Files.readAllBytes(first)).isEqualTo(bytes("old ACL"));
            } else {
                assertThat(first).doesNotExist();
            }
            assertThat(Files.readAllBytes(second)).isEqualTo(bytes("old roles"));
            try (java.util.stream.Stream<Path> children = Files.list(first.getParent())) {
                assertThat(children.toList()).containsExactlyElementsOf(firstExists ? List.of(first) : List.of());
            }
            try (java.util.stream.Stream<Path> children = Files.list(second.getParent())) {
                assertThat(children.toList()).containsExactly(second);
            }
        } finally {
            Files.setPosixFilePermissions(second.getParent(), permissions);
        }
        storage.save(new AccessControlSnapshot(changed, Optional.empty()),
                new AccessControlSaveRequest("retry both", UserEmail.EMPTY));
        assertThat(Files.readAllBytes(first)).isEqualTo(bytes("new ACL"));
        assertThat(Files.readAllBytes(second)).isEqualTo(bytes("new roles"));
        for (Path file : List.of(first, second)) {
            try (java.util.stream.Stream<Path> children = Files.list(file.getParent())) {
                assertThat(children.toList()).containsExactly(file);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"file,true", "file,false", "directory,true", "directory,false",
            "lock,true", "lock,false", "inside,true"})
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void rejectsLinkedDocumentsDirectoriesAndLocks(String kind, boolean targetExists) throws Exception {
        Path directory = Files.createDirectory(root.resolve("acl"));
        Path outside = kind.equals("inside") ? directory.resolve("inside.xml") : root.resolve("outside");
        Path link;
        if (kind.equals("directory")) {
            link = directory.resolve("config");
            if (targetExists) {
                Files.createDirectory(outside);
                Files.write(outside.resolve("orion.xml"), bytes("untouched"));
            }
        } else {
            Files.createDirectories(directory.resolve("config"));
            link = directory.resolve(kind.equals("lock") ? ".orion-configuration.lock" : ACL_PATH);
            if (targetExists) {
                Files.write(outside, bytes("untouched"));
            }
        }
        Files.createSymbolicLink(link, outside);
        LocalAccessControlStorage storage = new LocalAccessControlStorage(config(directory));
        Result<AccessControlSnapshot> loaded = storage.load();
        assertThat(loaded).isInstanceOf(Result.Failure.class);
        assertThat(((Result.Failure<?>) loaded).code()).isEqualTo(Result.FailureCode.GENERAL);
        assertThatThrownBy(() -> storage.save(
                AccessControlSnapshot.singleFile(ACL_PATH, bytes("replacement")),
                new AccessControlSaveRequest("save", UserEmail.EMPTY)))
                .isInstanceOf(RuntimeException.class);
        assertThat(Files.isSymbolicLink(link)).isTrue();
        if (targetExists) {
            Path target = kind.equals("directory") ? outside.resolve("orion.xml") : outside;
            assertThat(Files.readAllBytes(target)).isEqualTo(bytes("untouched"));
        } else {
            assertThat(outside).doesNotExist();
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void allowsASymbolicLinkForTheTrustedRoot() throws Exception {
        Path actual = Files.createDirectory(root.resolve("actual"));
        Path link = Files.createSymbolicLink(root.resolve("root-link"), actual);
        LocalAccessControlStorage storage = new LocalAccessControlStorage(config(link));
        storage.save(AccessControlSnapshot.singleFile(ACL_PATH, bytes("ACL")),
                new AccessControlSaveRequest("save", UserEmail.EMPTY));
        assertThat(storage.load().valueOrFailure("trusted root").files().get(ACL_PATH)).isEqualTo(bytes("ACL"));
        assertThat(Files.readAllBytes(actual.resolve(ACL_PATH))).isEqualTo(bytes("ACL"));
    }

    @Test
    void loadsAndSavesMultipleConfiguredFilesWithConfiguredPrimaryPath() throws Exception {
        Files.createDirectories(root.resolve("config"));
        Files.createDirectories(root.resolve("roles"));
        Files.write(root.resolve(ACL_PATH), bytes("existing ACL"));
        Files.write(root.resolve(ROLES_PATH), bytes("existing roles"));
        BootstrapConfigurationSourceConfig configuration = config(root);
        configuration.setPaths(List.of(ROLES_PATH, ACL_PATH));
        LocalAccessControlStorage storage = new LocalAccessControlStorage(configuration);

        AccessControlSnapshot loaded = storage.load().valueOrFailure("configured files");

        assertThat(storage.primaryPath()).isEqualTo(ROLES_PATH);
        assertThat(loaded.files()).containsOnlyKeys(ROLES_PATH, ACL_PATH)
                .containsEntry(ROLES_PATH, bytes("existing roles"))
                .containsEntry(ACL_PATH, bytes("existing ACL"));

        storage.save(
                new AccessControlSnapshot(Map.of(
                        ROLES_PATH, bytes("updated roles"),
                        ACL_PATH, bytes("updated ACL")), Optional.empty()),
                new AccessControlSaveRequest("update configured files", UserEmail.EMPTY));

        assertThat(Files.readAllBytes(root.resolve(ROLES_PATH))).isEqualTo(bytes("updated roles"));
        assertThat(Files.readAllBytes(root.resolve(ACL_PATH))).isEqualTo(bytes("updated ACL"));
        assertThat(storage.load().valueOrFailure("updated files").files())
                .containsOnlyKeys(ROLES_PATH, ACL_PATH)
                .containsEntry(ROLES_PATH, bytes("updated roles"))
                .containsEntry(ACL_PATH, bytes("updated ACL"));
    }

    @Test
    void preservesUnchangedFilesAndTheirModificationTimes() throws Exception {
        BootstrapConfigurationSourceConfig configuration = config(root);
        configuration.setPaths(List.of(ACL_PATH, ROLES_PATH));
        LocalAccessControlStorage storage = new LocalAccessControlStorage(configuration);
        AccessControlSaveRequest request = new AccessControlSaveRequest("save", UserEmail.EMPTY);
        storage.save(new AccessControlSnapshot(Map.of(ACL_PATH, bytes("before"), ROLES_PATH, bytes("roles")),
                Optional.empty()), request);
        FileTime originalTime = FileTime.fromMillis(1_000_000);
        Files.setLastModifiedTime(root.resolve(ROLES_PATH), originalTime);
        AccessControlSnapshot loaded = storage.load().valueOrFailure("loaded");
        storage.save(new AccessControlSnapshot(Map.of(ACL_PATH, bytes("after"), ROLES_PATH, bytes("roles")),
                loaded.version()), request);
        assertThat(Files.readAllBytes(root.resolve(ACL_PATH))).isEqualTo(bytes("after"));
        assertThat(Files.getLastModifiedTime(root.resolve(ROLES_PATH))).isEqualTo(originalTime);
        Files.setLastModifiedTime(root.resolve(ACL_PATH), originalTime);
        storage.save(storage.load().valueOrFailure("updated"), request);
        assertThat(Files.getLastModifiedTime(root.resolve(ACL_PATH))).isEqualTo(originalTime);
        assertThat(Files.getLastModifiedTime(root.resolve(ROLES_PATH))).isEqualTo(originalTime);
    }

    @Test
    void rejectsAnInvalidLaterPathBeforeWritingAnyDocument() throws Exception {
        LocalAccessControlStorage storage = new LocalAccessControlStorage(config(root));
        AccessControlSaveRequest request = new AccessControlSaveRequest("save", UserEmail.EMPTY);
        storage.save(AccessControlSnapshot.singleFile(ACL_PATH, bytes("before")), request);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(ACL_PATH, bytes("after"));
        files.put("../escape.xml", bytes("invalid"));
        assertThatThrownBy(() -> storage.save(new AccessControlSnapshot(files, Optional.empty()), request))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readAllBytes(root.resolve(ACL_PATH))).isEqualTo(bytes("before"));
    }

    @Test
    void failsContentComparisonBeforeWritingAnyDocument() throws Exception {
        LocalAccessControlStorage storage = new LocalAccessControlStorage(config(root));
        AccessControlSaveRequest request = new AccessControlSaveRequest("save", UserEmail.EMPTY);
        storage.save(AccessControlSnapshot.singleFile(ACL_PATH, bytes("before")), request);
        Files.createDirectories(root.resolve(ROLES_PATH));
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(ACL_PATH, bytes("after"));
        files.put(ROLES_PATH, bytes("roles"));
        assertThatThrownBy(() -> storage.save(new AccessControlSnapshot(files, Optional.empty()), request))
                .isInstanceOf(RuntimeException.class).hasCauseInstanceOf(java.io.IOException.class);
        assertThat(Files.readAllBytes(root.resolve(ACL_PATH))).isEqualTo(bytes("before"));
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void resolverUsesLocalStorageForFilesystemSources(boolean fileUri, boolean createIfMissing)
            throws Exception {
        Files.createDirectories(root.resolve("config"));
        Files.createDirectories(root.resolve("roles"));
        Files.write(root.resolve(ACL_PATH), bytes("resolved ACL"));
        Files.write(root.resolve(ROLES_PATH), bytes("resolved roles"));
        String location = fileUri ? root.toUri().toString() : root.toString();
        ResolvedBootstrapSource source = new ResolvedBootstrapSource(
                BootstrapRepositorySources.CONFIGURATION,
                location,
                Optional.empty(),
                "refs/heads/main",
                List.of(ROLES_PATH, ACL_PATH),
                Optional.empty(),
                createIfMissing);

        AccessControlStorage storage = new AccessControlStorageResolver(
                new BootstrapRepositorySources(List.of(source)),
                new InMemoryNativeGitRepositoryProvider()).resolve();

        assertThat(storage).isInstanceOf(LocalAccessControlStorage.class);
        assertThat(storage.primaryPath()).isEqualTo(ROLES_PATH);
        assertThat(storage.createIfMissing()).isEqualTo(createIfMissing);
        assertThat(storage.load().valueOrFailure("resolved filesystem ACL").files())
                .containsOnlyKeys(ROLES_PATH, ACL_PATH)
                .containsEntry(ROLES_PATH, bytes("resolved roles"))
                .containsEntry(ACL_PATH, bytes("resolved ACL"));
    }

    @Test
    void rejectsAStaleRevisionAfterASecondaryFileChanges() throws Exception {
        var configuration = config(root);
        configuration.setPaths(List.of(ACL_PATH, ROLES_PATH));
        var storage = new LocalAccessControlStorage(configuration);
        storage.save(new AccessControlSnapshot(Map.of(ACL_PATH, bytes("original"), ROLES_PATH, bytes("roles")),
                Optional.empty()), new AccessControlSaveRequest("seed", UserEmail.EMPTY));
        AccessControlSnapshot before = storage.load().valueOrFailure("snapshot");
        Files.write(root.resolve(ROLES_PATH), bytes("new roles"));

        assertThatThrownBy(() -> storage.save(new AccessControlSnapshot(
                Map.of(ACL_PATH, bytes("stale"), ROLES_PATH, bytes("roles")), before.version()),
                new AccessControlSaveRequest("stale", UserEmail.EMPTY)))
                .isInstanceOf(AccessControlConcurrentUpdateException.class);
        assertThat(Files.readAllBytes(root.resolve(ACL_PATH))).isEqualTo(bytes("original"));
        assertThat(Files.readAllBytes(root.resolve(ROLES_PATH))).isEqualTo(bytes("new roles"));
    }

    @Test
    void concurrentOwnersCannotBothSaveTheSameRevision() throws Exception {
        var first = new LocalAccessControlStorage(config(root));
        var second = new LocalAccessControlStorage(config(root));
        var request = new AccessControlSaveRequest("save", UserEmail.EMPTY);
        first.save(AccessControlSnapshot.singleFile(ACL_PATH, bytes("initial")), request);
        var revision = first.load().valueOrFailure("snapshot").version();
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (var storage : List.of(first, second)) {
                results.add(executor.submit(() -> {
                    barrier.await();
                    try {
                        storage.save(new AccessControlSnapshot(Map.of(ACL_PATH,
                                bytes(storage == first ? "first" : "second")), revision), request);
                        return true;
                    } catch (AccessControlConcurrentUpdateException conflict) {
                        return false;
                    }
                }));
            }
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(true, false);
        }
        var reopened = new LocalAccessControlStorage(config(root));
        assertThat(reopened.load().valueOrFailure("saved").version()).isNotEqualTo(revision);
    }

    private static BootstrapConfigurationSourceConfig config(Path directory) {
        BootstrapConfigurationSourceConfig configuration = new BootstrapConfigurationSourceConfig();
        configuration.setLocation(directory.toUri().toString());
        configuration.setPath(ACL_PATH);
        return configuration;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
