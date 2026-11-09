package pro.deta.orion.storage;

import pro.deta.orion.util.VcRevision;
import pro.deta.orion.util.VcModificationException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class LocalBlobStorageSupport {
    private LocalBlobStorageSupport() {}

    static Path location(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("Blob path must not be null");
        }
        Path normalized = ProtectedFiles.normalizeLocation(path);
        if (normalized.getFileName() == null) {
            throw new IllegalArgumentException("Blob path must include a file name");
        }
        return normalized;
    }

    static VcRevision revision(Path path) {
        byte[] content = readContent(path, new VcRevision.Latest());
        if (content == null) return new VcRevision.Absent();
        try {
            return new VcRevision.Stored(BlobRevisions.sha256(content));
        } finally {
            Arrays.fill(content, (byte) 0);
        }
    }

    static byte[] readContent(Path path, VcRevision.ReadTarget revision) {
        Objects.requireNonNull(revision, "revision");
        try {
            ProtectedFiles.validatePublicationDirectory(path.getParent());
            byte[] content = ProtectedFiles.readOwnerOnlyFile(path, "Blob location");
            if (revision instanceof VcRevision.Absent absent) {
                VcRevision.Stored actual = new VcRevision.Stored(BlobRevisions.sha256(content));
                Arrays.fill(content, (byte) 0);
                throw new VcModificationException(absent, actual);
            }
            if (revision instanceof VcRevision.Stored stored
                    && !stored.value().equals(BlobRevisions.sha256(content))) {
                Arrays.fill(content, (byte) 0);
                throw new IllegalArgumentException("Blob revision is no longer available: " + stored.value());
            }
            return content;
        } catch (NoSuchFileException missing) {
            if (!(revision instanceof VcRevision.Stored)) return null;
            throw new UncheckedIOException("Cannot read local blob: " + path, missing);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read local blob: " + path, failure);
        }
    }

    static void write(Path path, byte[] content) {
        Path parent = path.getParent();
        Path temp = parent.resolve("." + path.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            ProtectedFiles.createDirectories(parent);
            try {
                writeDurably(temp, parent, content);
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                ProtectedFiles.validateOwnerOnlyRegularFile(path, "Blob location");
                forceDirectory(parent);
            } catch (IOException | RuntimeException | Error failure) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot publish local blob: " + path, failure);
        }
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
        if (!ProtectedFiles.supportsPosix(parent)) {
            return;
        }
        try (FileChannel directory = FileChannel.open(parent, StandardOpenOption.READ)) {
            directory.force(true);
        }
    }

}
