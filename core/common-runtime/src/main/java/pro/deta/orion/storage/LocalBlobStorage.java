package pro.deta.orion.storage;

import java.io.IOException;
import java.util.function.Consumer;
import pro.deta.orion.util.Subscription;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Stores opaque bytes in an owner-only file with atomic, conditional publication.
 * TODO Implement change subscriptions; subscriptions currently receive no notifications.
 */
public class LocalBlobStorage implements BlobStorage {
    private static final int JVM_LOCK_COUNT = 64;
    private static final ReentrantLock[] JVM_LOCKS = createJvmLocks();

    private final Path path;

    public LocalBlobStorage(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("Blob path must not be null");
        }
        this.path = ProtectedFiles.normalizeLocation(path);
        if (this.path.getFileName() == null) {
            throw new IllegalArgumentException("Blob path must include a file name");
        }
    }

    public Path path() {
        return path;
    }

    @Override
    public Subscription subscribe(Consumer<BlobChanged> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        return () -> {};
    }

    @Override
    public Content load() throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return new Content(null, null);
        ProtectedFiles.validatePublicationDirectory(path.getParent());
        byte[] bytes = ProtectedFiles.readOwnerOnlyFile(path, "Blob location");
        return new Content(BlobRevisions.sha256(bytes), bytes);
    }

    @Override
    public byte[] read(String revision) throws IOException {
        Objects.requireNonNull(revision, "revision");
        ProtectedFiles.validatePublicationDirectory(path.getParent());
        byte[] bytes = ProtectedFiles.readOwnerOnlyFile(path, "Blob location");
        if (!revision.equals(BlobRevisions.sha256(bytes))) {
            Arrays.fill(bytes, (byte) 0);
            throw new BlobStorageConflictException("Blob revision is no longer available: " + revision);
        }
        return bytes;
    }

    @Override
    public String write(Modification modification) throws IOException {
        byte[] bytes = modification.bytes();
        String expectedVersion = modification.expectedRevision();
        if (bytes == null) {
            throw new IllegalArgumentException("Blob bytes must not be null");
        }
        Path parent = path.getParent();
        ProtectedFiles.createDirectories(parent);
        ReentrantLock jvmLock = jvmLock();
        jvmLock.lock();
        try {
            try (
                    FileChannel lockChannel = openLockFile(parent);
                    FileLock ignored = lockChannel.lock()
            ) {
                String currentVersion = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                        ? BlobRevisions.sha256(
                                ProtectedFiles.readOwnerOnlyFile(path, "Blob location"))
                        : null;
                if (!matchesExpectedVersion(currentVersion, expectedVersion)) {
                    throw new BlobStorageConflictException("Blob store changed before save");
                }

                Path temp = tempPath(parent);
                try {
                    writeDurably(temp, parent, bytes);
                    moveIntoPlace(temp);
                    ProtectedFiles.validateOwnerOnlyRegularFile(path, "Blob location");
                    forceDirectory(parent);
                } finally {
                    Files.deleteIfExists(temp);
                }
            }
        } finally {
            jvmLock.unlock();
        }
        return BlobRevisions.sha256(bytes);
    }

    public int discardIncompleteWrites() throws IOException {
        Path parent = path.getParent();
        if (parent == null || !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        ProtectedFiles.validatePublicationDirectory(parent);
        ReentrantLock jvmLock = jvmLock();
        jvmLock.lock();
        try {
            try (
                    FileChannel lockChannel = openLockFile(parent);
                    FileLock ignored = lockChannel.lock()
            ) {
                int discarded = 0;
                try (DirectoryStream<Path> candidates = Files.newDirectoryStream(parent)) {
                    for (Path candidate : candidates) {
                        String fileName = candidate.getFileName().toString();
                        if (!fileName.startsWith(tempFilePrefix()) || !fileName.endsWith(".tmp")) {
                            continue;
                        }
                        ProtectedFiles.validateOwnerOnlyRegularFile(
                                candidate,
                                "Incomplete blob publication");
                        Files.delete(candidate);
                        discarded++;
                    }
                }
                if (discarded > 0) {
                    forceDirectory(parent);
                }
                return discarded;
            }
        } finally {
            jvmLock.unlock();
        }
    }

    private void moveIntoPlace(Path temp) throws IOException {
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private FileChannel openLockFile(Path parent) throws IOException {
        Path lock = lockPath(parent);
        try (FileChannel created = FileChannel.open(
                lock,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                ProtectedFiles.ownerOnlyFileAttributes(parent))) {
            created.force(true);
            forceDirectory(parent);
        } catch (FileAlreadyExistsException ignored) {
            // The persistent lock file coordinates every writer for this store.
        }
        ProtectedFiles.validateOwnerOnlyRegularFile(lock, "Blob lock");
        return FileChannel.open(lock, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    private static void writeDurably(Path temp, Path parent, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(
                temp,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                ProtectedFiles.ownerOnlyFileAttributes(parent))) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static void forceDirectory(Path parent) throws IOException {
        if (parent == null || !ProtectedFiles.supportsPosix(parent)) {
            return;
        }
        try (FileChannel directory = FileChannel.open(parent, StandardOpenOption.READ)) {
            directory.force(true);
        }
    }

    private Path tempPath(Path parent) {
        String tempFileName = tempFilePrefix() + UUID.randomUUID() + ".tmp";
        if (parent == null) {
            return Path.of(tempFileName).toAbsolutePath().normalize();
        }
        return parent.resolve(tempFileName);
    }

    private String tempFilePrefix() {
        return "." + path.getFileName() + ".";
    }

    private ReentrantLock jvmLock() {
        int index = Math.floorMod(lockPath(path.getParent()).hashCode(), JVM_LOCKS.length);
        return JVM_LOCKS[index];
    }

    private Path lockPath(Path parent) {
        String lockFileName = "." + path.getFileName() + ".lock";
        if (parent == null) {
            return Path.of(lockFileName).toAbsolutePath().normalize();
        }
        return parent.resolve(lockFileName);
    }

    private static boolean matchesExpectedVersion(String currentVersion, String expectedVersion) {
        if (currentVersion == null) {
            return expectedVersion == null;
        }
        return currentVersion.equals(expectedVersion);
    }

    private static ReentrantLock[] createJvmLocks() {
        ReentrantLock[] locks = new ReentrantLock[JVM_LOCK_COUNT];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
        return locks;
    }
}
