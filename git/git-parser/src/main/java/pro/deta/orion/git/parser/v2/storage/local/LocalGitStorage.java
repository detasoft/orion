package pro.deta.orion.git.parser.v2.storage.local;

import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.read.GitPackRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.storage.GitStorageAccess;
import pro.deta.orion.git.parser.v2.storage.shared.PackByteSource;
import pro.deta.orion.git.parser.v2.storage.shared.PackHandle;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static pro.deta.orion.git.parser.v2.storage.shared.PackSupport.closeUnreturned;

/** Local pack bytes keyed by an internal ID; all object metadata and visibility belong to the index. */
public final class LocalGitStorage implements GitStorageApi {
    private final Path directory;
    private boolean closed;

    public LocalGitStorage(Path repository) throws IOException {
        directory = repository.toRealPath().resolve("packs");
        Files.createDirectories(directory);
    }

    @Override
    public synchronized GitStorageAccess createAccess() throws IOException {
        if (closed) throw new ClosedChannelException();
        return new Access();
    }

    private final class Access implements GitStorageAccess {
        private final List<PackHandle> writers = new ArrayList<>();
        private boolean closed;

        @Override
        public PackHandle newPack(PackId packId) throws IOException {
            requireOpen();
            PackHandle bytes = FilePackHandle.open(path(packId), StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
            try (FileChannel parent = FileChannel.open(directory, StandardOpenOption.READ)) {
                parent.force(true);
            } catch (IOException | RuntimeException | Error failure) {
                closeUnreturned(bytes, failure);
                throw failure;
            }
            writers.add(bytes);
            return bytes;
        }

        @Override
        public <R> R readPack(PackId packId, long offset, long length, GitPackRead<R> reader) throws IOException {
            requireOpen();
            Objects.requireNonNull(reader, "reader");
            R result = null;
            try (PackHandle bytes = FilePackHandle.open(path(packId), StandardOpenOption.READ)) {
                if (offset < 0 || length < 0 || offset > bytes.size() || length > bytes.size() - offset) {
                    throw new EOFException("Invalid stored pack byte range");
                }
                try (BufferedByteInputV2 input = new BufferedByteInputV2(
                        new PackByteSource(bytes, offset, offset + length))) {
                    result = Objects.requireNonNull(reader.read(length, input), "reader result");
                    return result;
                }
            } catch (IOException | RuntimeException | Error failure) {
                closeUnreturned(result, failure);
                throw failure;
            }
        }

        @Override
        public boolean exists(PackId packId) throws IOException {
            requireOpen();
            return Files.isRegularFile(path(packId));
        }

        @Override
        public Set<PackId> packIds() throws IOException {
            requireOpen();
            Set<PackId> ids = new HashSet<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "pack-*.data")) {
                for (Path entry : entries) {
                    if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) continue;
                    String filename = entry.getFileName().toString();
                    String raw = filename.substring("pack-".length(), filename.length() - ".data".length());
                    try {
                        PackId id = new PackId(raw);
                        if (id.toString().equals(raw)) ids.add(id);
                    } catch (IllegalArgumentException ignored) {
                        // Files unrelated to stored packs are not inventory entries.
                    }
                }
            }
            return Set.copyOf(ids);
        }

        private void requireOpen() throws ClosedChannelException {
            if (closed) throw new ClosedChannelException();
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            IOException failure = null;
            for (PackHandle writer : writers) {
                try {
                    writer.close();
                } catch (IOException error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
            writers.clear();
            if (failure != null) throw failure;
        }
    }

    private Path path(PackId id) {
        return directory.resolve("pack-" + Objects.requireNonNull(id, "packId") + ".data");
    }

    /** Deletes an expired pack only when its index owner has excluded publication and active writers. */
    public synchronized boolean deleteExpiredPack(PackId id, Instant cutoff) throws IOException {
        if (closed) throw new ClosedChannelException();
        Path pack = path(id);
        if (!Files.isRegularFile(pack, LinkOption.NOFOLLOW_LINKS)
                || !Files.getLastModifiedTime(pack, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) {
            return false;
        }
        return Files.deleteIfExists(pack);
    }

    @Override
    public synchronized void close() { closed = true; }
}
