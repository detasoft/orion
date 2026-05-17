package pro.deta.orion.keymaterial;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;

public class LocalKeyMaterialContentStore implements KeyMaterialContentStore {
    private final Path path;

    public LocalKeyMaterialContentStore(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("Key material path must not be null");
        }
        this.path = path.toAbsolutePath().normalize();
        if (this.path.getFileName() == null) {
            throw new IllegalArgumentException("Key material path must include a file name");
        }
    }

    public Path path() {
        return path;
    }

    @Override
    public Optional<KeyMaterialSnapshot> read() throws IOException {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException("Key material location is not a file: " + path);
        }
        byte[] bytes = Files.readAllBytes(path);
        return Optional.of(new KeyMaterialSnapshot(bytes, KeyMaterialVersions.sha256(bytes)));
    }

    @Override
    public String write(byte[] bytes, String expectedVersion) throws IOException {
        if (bytes == null) {
            throw new IllegalArgumentException("Key material bytes must not be null");
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (
                FileChannel lockChannel = FileChannel.open(
                        lockPath(parent),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
                FileLock ignored = lockChannel.lock()
        ) {
            if (Files.exists(path) && !Files.isRegularFile(path)) {
                throw new IOException("Key material location is not a file: " + path);
            }
            String currentVersion = Files.exists(path)
                    ? KeyMaterialVersions.sha256(Files.readAllBytes(path))
                    : null;
            if (!matchesExpectedVersion(currentVersion, expectedVersion)) {
                throw new KeyMaterialStoreConflictException("Key material store changed before save");
            }

            Path temp = tempPath(parent);
            try {
                Files.write(temp, bytes);
                moveIntoPlace(temp);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
        return KeyMaterialVersions.sha256(bytes);
    }

    private void moveIntoPlace(Path temp) throws IOException {
        try {
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path tempPath(Path parent) {
        String fileName = path.getFileName().toString();
        String tempFileName = "." + fileName + "." + UUID.randomUUID() + ".tmp";
        if (parent == null) {
            return Path.of(tempFileName).toAbsolutePath().normalize();
        }
        return parent.resolve(tempFileName);
    }

    private Path lockPath(Path parent) {
        String fileName = path.getFileName().toString();
        String lockFileName = "." + fileName + ".lock";
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
}
