package pro.deta.orion.agent.server.registry;

import pro.deta.orion.lifecycle.state.TestOnly;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class SessionRegistryFileOperations {
    private static final String TRANSACTION_PREFIX = ".session-transaction-";
    private static final String MANIFEST = "manifest";
    private static final int MANIFEST_MAGIC = 0x4f525354;

    Path prepareRoot(Path requestedRoot) throws IOException {
        List<Path> missing = new ArrayList<>();
        Path current = requestedRoot;
        while (current != null && !Files.exists(current)) {
            missing.add(current);
            current = current.getParent();
        }
        if (current == null || !Files.isDirectory(current)) {
            throw new IOException("Could not find an existing session registry root ancestor");
        }
        Collections.reverse(missing);
        for (Path directory : missing) {
            try {
                Files.createDirectory(directory);
            } catch (FileAlreadyExistsException failure) {
                if (!Files.isDirectory(directory)) {
                    throw failure;
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
        Path lockPath = root.resolve(".session-registry.lock");
        try (FileChannel created = FileChannel.open(
                lockPath,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            created.force(true);
            forceDirectory(root);
        } catch (FileAlreadyExistsException ignored) {
            BasicFileAttributes attributes = Files.readAttributes(
                    lockPath,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new IOException("Session registry lock is not a regular file");
            }
        }
        FileChannel channel = FileChannel.open(
                lockPath,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException failure) {
                throw new RootInUseException(failure);
            }
            if (lock == null) {
                throw new RootInUseException(null);
            }
            return new RootOwnership(channel, lock);
        } catch (IOException | RootInUseException | RuntimeException failure) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    List<Path> recordFiles(Path root) throws IOException {
        List<Path> records = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root, "*.session")) {
            for (Path entry : entries) {
                records.add(entry);
            }
        }
        records.sort(Path::compareTo);
        return records;
    }

    byte[] readRecord(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() > SessionRecordCodec.MAX_RECORD_BYTES) {
            throw new StoredRecordException("Session record is not a bounded regular file", null);
        }
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes = ByteBuffer.allocate(Math.toIntExact(attributes.size()));
            while (bytes.hasRemaining() && channel.read(bytes) >= 0) {
                // Continue until the complete bounded snapshot is read.
            }
            if (bytes.hasRemaining() || channel.read(ByteBuffer.allocate(1)) >= 0) {
                throw new IOException("Session record changed while it was being read");
            }
            return bytes.array();
        }
    }

    void publishBatch(Path root, Map<Path, byte[]> records) throws PublicationException {
        Path transaction = root.resolve(TRANSACTION_PREFIX + UUID.randomUUID());
        boolean prepared = false;
        boolean manifestMayExist = false;
        try {
            Files.createDirectory(transaction);
            Map<String, byte[]> digests = new LinkedHashMap<>();
            for (Map.Entry<Path, byte[]> entry : records.entrySet()) {
                String name = entry.getKey().getFileName().toString();
                writeFile(transaction.resolve(name), entry.getValue());
                digests.put(name, digest(entry.getValue()));
            }
            manifestMayExist = true;
            writeManifest(transaction.resolve(MANIFEST), digests);
            beforeTransactionDirectoryForce();
            forceDirectory(transaction);
            forceDirectory(root);
            prepared = true;
            finishPreparedTransaction(root, transaction, digests);
        } catch (IOException failure) {
            boolean indeterminate = prepared;
            if (!prepared) {
                try {
                    cleanupUnprepared(transaction);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                    indeterminate = manifestMayExist;
                }
            }
            throw new PublicationException(indeterminate, failure);
        }
    }

    void recoverTransactions(Path root) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root, TRANSACTION_PREFIX + "*")) {
            for (Path transaction : entries) {
                BasicFileAttributes attributes = Files.readAttributes(
                        transaction,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory()) {
                    throw new StoredRecordException("Session transaction is not a directory", null);
                }
                Path manifest = transaction.resolve(MANIFEST);
                if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
                    cleanupUnprepared(transaction);
                    continue;
                }
                finishPreparedTransaction(root, transaction, readManifest(manifest));
            }
        }
    }

    private void finishPreparedTransaction(
            Path root,
            Path transaction,
            Map<String, byte[]> digests) throws IOException {
        int movedRecords = 0;
        for (Map.Entry<String, byte[]> entry : digests.entrySet()) {
            Path source = transaction.resolve(entry.getKey());
            Path target = root.resolve(entry.getKey());
            if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                if (!MessageDigest.isEqual(digest(readRecord(source)), entry.getValue())) {
                    throw new StoredRecordException("Prepared session record digest does not match", null);
                }
                Files.move(
                        source,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                afterRecordMove(++movedRecords);
            } else if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    || !MessageDigest.isEqual(digest(readRecord(target)), entry.getValue())) {
                throw new StoredRecordException("Prepared session transaction is incomplete", null);
            }
        }
        forceDirectory(root);
        Files.delete(transaction.resolve(MANIFEST));
        Files.delete(transaction);
        forceDirectory(root);
    }

    private void writeManifest(Path path, Map<String, byte[]> entries) throws IOException {
        try (FileChannel channel = FileChannel.open(
                path,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
                DataOutputStream output = new DataOutputStream(
                        java.nio.channels.Channels.newOutputStream(channel))) {
            output.writeInt(MANIFEST_MAGIC);
            output.writeInt(entries.size());
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.writeUTF(entry.getKey());
                output.writeInt(entry.getValue().length);
                output.write(entry.getValue());
            }
            output.flush();
            channel.force(true);
        }
    }

    private Map<String, byte[]> readManifest(Path path) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (DataInputStream input = new DataInputStream(Files.newInputStream(
                path,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS))) {
            if (input.readInt() != MANIFEST_MAGIC) {
                throw new StoredRecordException("Session transaction manifest has an invalid header", null);
            }
            int count = input.readInt();
            if (count < 0 || count > 100_000) {
                throw new StoredRecordException("Session transaction manifest count is invalid", null);
            }
            for (int index = 0; index < count; index++) {
                String name = input.readUTF();
                if (!name.matches("[0-9a-f]{64}\\.session") || entries.containsKey(name)) {
                    throw new StoredRecordException("Session transaction contains an invalid target", null);
                }
                int length = input.readInt();
                if (length != 32) {
                    throw new StoredRecordException("Session transaction digest length is invalid", null);
                }
                byte[] digest = input.readNBytes(length);
                if (digest.length != length) {
                    throw new EOFException("Session transaction digest is truncated");
                }
                entries.put(name, digest);
            }
            if (input.read() >= 0) {
                throw new StoredRecordException("Session transaction manifest has trailing bytes", null);
            }
        }
        return entries;
    }

    private void writeFile(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(
                path,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    @TestOnly
    void afterRecordMove(int movedRecords) throws IOException {
    }

    @TestOnly
    void beforeTransactionDirectoryForce() throws IOException {
    }

    @TestOnly
    void deleteUnpreparedEntry(Path entry) throws IOException {
        Files.delete(entry);
    }

    @TestOnly
    void forceCleanupDirectory(Path directory) throws IOException {
        forceDirectory(directory);
    }

    private void cleanupUnprepared(Path transaction) throws IOException {
        if (!Files.exists(transaction, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        IOException failure = null;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(transaction)) {
            for (Path entry : entries) {
                try {
                    deleteUnpreparedEntry(entry);
                } catch (IOException cleanupFailure) {
                    failure = append(failure, cleanupFailure);
                }
            }
        } catch (IOException cleanupFailure) {
            failure = append(failure, cleanupFailure);
        }
        try {
            Files.delete(transaction);
        } catch (IOException cleanupFailure) {
            failure = append(failure, cleanupFailure);
        }
        try {
            forceCleanupDirectory(transaction.getParent());
        } catch (IOException cleanupFailure) {
            failure = append(failure, cleanupFailure);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static IOException append(IOException current, IOException addition) {
        if (current == null) {
            return addition;
        }
        current.addSuppressed(addition);
        return current;
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void requireDirectory(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()) {
            throw new IOException("Session registry root is not a directory");
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    static final class StoredRecordException extends IOException {
        StoredRecordException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static final class RootInUseException extends Exception {
        private RootInUseException(Throwable cause) {
            super("Session registry root is already owned", cause);
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
            } catch (IOException releaseFailure) {
                failure = releaseFailure;
            }
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure = append(failure, closeFailure);
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
