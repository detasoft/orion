package pro.deta.orion.agent.server.registry;

import pro.deta.orion.lifecycle.state.TestOnly;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

class AgentRegistryFileOperations {
    Path prepareRoot(Path requestedRoot) throws IOException {
        List<Path> missing = new ArrayList<>();
        Path current = requestedRoot;
        while (current != null && !Files.exists(current)) {
            missing.add(current);
            current = current.getParent();
        }
        if (current == null || !Files.isDirectory(current)) {
            throw new IOException("Could not find an existing agent registry root ancestor");
        }
        Collections.reverse(missing);
        for (Path directory : missing) {
            try {
                Files.createDirectory(directory);
            } catch (FileAlreadyExistsException e) {
                if (!Files.isDirectory(directory)) {
                    throw e;
                }
            }
            forceDirectory(directory.toRealPath());
            forceDirectory(directory.toRealPath().getParent());
        }
        Path root = requestedRoot.toRealPath();
        requireDirectory(root);
        return root;
    }

    RootOwnership acquireRoot(Path root) throws IOException, RootInUseException {
        Path lockPath = root.resolve(".agent-registry.lock");
        try (FileChannel created = FileChannel.open(
                lockPath,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            created.force(true);
            forceDirectory(root);
        } catch (FileAlreadyExistsException ignored) {
            BasicFileAttributes attributes = Files.readAttributes(
                    lockPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new IOException("Agent registry lock is not a regular file");
            }
        }

        FileChannel channel = FileChannel.open(
                lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                throw new RootInUseException(e);
            }
            if (lock == null) {
                throw new RootInUseException(null);
            }
            return new RootOwnership(channel, lock);
        } catch (IOException | RootInUseException | RuntimeException e) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    List<Path> recordFiles(Path root) throws IOException {
        List<Path> records = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root, "*.agent")) {
            for (Path entry : entries) {
                records.add(entry);
            }
        }
        records.sort(Path::compareTo);
        return records;
    }

    byte[] readRecord(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() > AgentRecordCodec.MAX_RECORD_BYTES) {
            throw new StoredRecordException("Agent record is not a bounded regular file");
        }
        try (FileChannel channel = FileChannel.open(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes = ByteBuffer.allocate(Math.toIntExact(attributes.size()));
            while (bytes.hasRemaining() && channel.read(bytes) >= 0) {
                // Continue until the complete bounded snapshot is read.
            }
            if (bytes.hasRemaining() || channel.read(ByteBuffer.allocate(1)) >= 0) {
                throw new IOException("Agent record changed while it was being read");
            }
            return bytes.array();
        }
    }

    void establishDurability(Path path, Path root) throws IOException {
        try (FileChannel channel = FileChannel.open(
                path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
        forceDirectory(root);
    }

    void publish(Path root, Path target, byte[] bytes) throws PublicationException {
        Path temporary = root.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        boolean publicationAttempted = false;
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                beforeTemporaryForce(temporary);
                channel.force(true);
            }
            beforePublication(temporary, target);
            publicationAttempted = true;
            moveIntoPlace(temporary, target);
            afterPublication(target);
            forceDirectory(root);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw new PublicationException(publicationAttempted, e);
        }
    }

    @TestOnly
    void beforeTemporaryForce(Path temporary) throws IOException {
    }

    @TestOnly
    void beforePublication(Path temporary, Path target) throws IOException {
    }

    @TestOnly
    void moveIntoPlace(Path temporary, Path target) throws IOException {
        Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    @TestOnly
    void afterPublication(Path target) throws IOException {
    }

    private static void requireDirectory(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()) {
            throw new IOException("Agent registry root is not a directory");
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    static final class StoredRecordException extends IOException {
        private StoredRecordException(String message) {
            super(message);
        }
    }

    static final class RootInUseException extends Exception {
        private RootInUseException(Throwable cause) {
            super("Agent registry root is already owned", cause);
        }
    }

    static final class PublicationException extends IOException {
        private final boolean indeterminate;

        private PublicationException(boolean indeterminate, IOException cause) {
            super(cause.getMessage(), cause);
            this.indeterminate = indeterminate;
        }

        boolean indeterminate() {
            return indeterminate;
        }
    }

    static final class RootOwnership implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private RootOwnership(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                lock.release();
            } catch (IOException e) {
                failure = e;
            }
            try {
                channel.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
