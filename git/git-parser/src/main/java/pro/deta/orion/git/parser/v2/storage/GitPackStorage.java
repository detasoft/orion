package pro.deta.orion.git.parser.v2.storage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.pack.PackObjectParser;
import pro.deta.orion.git.parser.v2.pack.PackUpload;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.git.parser.v2.read.ResolvedGitObjectRead;
import pro.deta.orion.net.io.BufferedByteInput;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.zip.DeflaterOutputStream;

/**
 * Owns repository pack staging, completion, publication, and scans of published indexes.
 * Each attempt has private files under incoming; publication uses packs/ab/cdef.pack and cdef.mv.
 * The finalized, closed index is moved last and is the publication marker. The pack rename is synced
 * before that marker, then the directory is synced again before commit returns. Orphan pack files and
 * staging are invisible; a subsequent verified upload can replace an orphan under the final PackId lock.
 * Published pairs are immutable, and rollback deletes only the attempt directory, never final paths.
 * One JVM owns repository writes; canonical-path GitLock coordinates all its facade instances.
 *
 * <p>Completion verifies retained bytes, appends each missing base as a full object, checks its ObjectId,
 * and updates the count and checksum with bounded buffers. Original offsets remain unchanged.
 * Base restoration inherits ResolvedGitObjectRead's current REF_DELTA-only and in-memory base limits.
 * Indexes are opened one at a time under the pack lock, avoiding overlapping MVStore file locks.
 * All index and directory handles close before invoking a reader, allowing nested and concurrent reads.
 * There is no repository-wide object index or retained index cache.
 * The repository directory must already exist; this backend creates its packs and incoming children.
 * A failed publication may already be visible if its final directory sync failed; rollback preserves it.
 * A later identical upload checks and syncs that pair before reporting success.
 */
final class GitPackStorage {
    private final Path packs;
    private final Path incoming;
    private final GitLock lock;

    GitPackStorage(Path repository) throws IOException {
        Path root = repository.toRealPath();
        packs = root.resolve("packs");
        incoming = root.resolve("incoming");
        Files.createDirectories(packs);
        Files.createDirectories(incoming);
        forceDirectory(root);
        lock = new GitLock(root);
    }

    PackUpload upload(GitStorageApi storage, BufferedByteInput source) throws IOException {
        Objects.requireNonNull(source, "source");
        Path directory = Files.createTempDirectory(incoming, "pack-");
        FilePackByteStore bytes = null;
        FilePackIndex index = null;
        try {
            bytes = new FilePackByteStore(directory.resolve("data.pack"));
            index = FilePackIndex.create(directory.resolve("data.mv"), directory.resolve("data.tmv"));
            return new PackUpload(storage, source, new Attempt(storage, directory, bytes, index));
        } catch (IOException | RuntimeException | Error failure) {
            if (index != null) {
                closeFailed(index, failure);
            }
            if (bytes != null) {
                closeFailed(bytes, failure);
            }
            closeFailed(() -> discard(directory), failure);
            throw failure;
        }
    }

    <R> Optional<R> read(ObjectId id, GitObjectRead<R> reader) throws IOException {
        Optional<Location> found = scan((packId, indexPath) -> {
            try (var lease = lockPack(packId); var index = StoredPackIndex.open(indexPath)) {
                return index.find(id).map(entry -> new Location(packId, entry));
            }
        });
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Location location = found.orElseThrow();
        R value = null;
        try (var bytes = FilePackByteStore.open(packPath(location.packId()))) {
            value = PackObjectParser.readStored(location.entry(), bytes, bytes.size() - 20, reader);
        } catch (IOException | RuntimeException | Error failure) {
            if (value instanceof AutoCloseable resource) {
                closeFailed(resource, failure);
            }
            throw failure;
        }
        return Optional.of(value);
    }

    Map<ObjectId, List<PackId>> find(Collection<ObjectId> ids) throws IOException {
        var result = new LinkedHashMap<ObjectId, List<PackId>>();
        for (ObjectId id : ids) {
            Objects.requireNonNull(id, "objectId");
        }
        scan((packId, path) -> {
            try (var lease = lockPack(packId); var index = StoredPackIndex.open(path)) {
                for (ObjectId id : ids) {
                    if (index.find(id).isPresent()) {
                        var locations = result.computeIfAbsent(id, ignored -> new ArrayList<>());
                        if (!locations.contains(packId)) {
                            locations.add(packId);
                        }
                    }
                }
            }
            return Optional.empty();
        });
        return result;
    }

    private <R> Optional<R> scan(PublishedIndexRead<R> reader) throws IOException {
        try (var shards = Files.newDirectoryStream(packs, "[0-9a-f][0-9a-f]")) {
            for (Path shard : shards) {
                try (var indexes = Files.newDirectoryStream(shard, "*.mv")) {
                    for (Path index : indexes) {
                        String name = index.getFileName().toString();
                        if (!name.matches("[0-9a-f]{38}\\.mv")) {
                            throw new IOException("Invalid published pack index name: " + index);
                        }
                        var id = new PackId(shard.getFileName() + name.substring(0, 38));
                        if (!Files.isRegularFile(packPath(id))) {
                            throw new IOException("Published pack is missing: " + id);
                        }
                        Optional<R> result = reader.read(id, index);
                        if (result.isPresent()) {
                            return result;
                        }
                    }
                }
            }
            return Optional.empty();
        } catch (DirectoryIteratorException failure) {
            throw failure.getCause();
        }
    }

    private Path packPath(PackId id) {
        String hex = id.toHex();
        return packs.resolve(hex.substring(0, 2)).resolve(hex.substring(2) + ".pack");
    }

    private void publish(Path directory, PackId id) throws IOException {
        try (var lease = lockPack(id)) {
            Path target = packPath(id);
            Path shard = target.getParent();
            Path index = shard.resolve(id.toHex().substring(2) + ".mv");
            Files.createDirectories(shard);
            forceDirectory(packs);
            if (Files.exists(index)) {
                try (var existing = StoredPackIndex.open(index);
                     var bytes = FilePackByteStore.open(target)) {
                    long size = bytes.size();
                    if (size < 32 || !MessageDigest.isEqual(digest(bytes, size - 20), id.toBytes())
                            || !MessageDigest.isEqual(readExactly(bytes, size - 20, 20), id.toBytes())) {
                        throw new IOException("Published pack checksum mismatch: " + id);
                    }
                }
                forceDirectory(shard);
                return;
            }
            Files.move(directory.resolve("data.pack"), target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(shard);
            Files.move(directory.resolve("data.mv"), index, StandardCopyOption.ATOMIC_MOVE);
            forceDirectory(shard);
        }
    }

    private GitLock.Lease lockPack(PackId id) throws IOException {
        try {
            return lock.lockPack(id);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while acquiring pack ownership", interrupted);
        }
    }

    private record Location(PackId packId, PackObjectParser.Entry entry) { }

    private static void forceDirectory(Path directory) throws IOException {
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void discard(Path directory) throws IOException {
        Files.deleteIfExists(directory.resolve("data.tmv"));
        Files.deleteIfExists(directory.resolve("data.mv"));
        Files.deleteIfExists(directory.resolve("data.pack"));
        Files.deleteIfExists(directory);
    }

    private interface PublishedIndexRead<R> {
        Optional<R> read(PackId id, Path index) throws IOException;
    }

    private final class Attempt implements PackUpload.Backend {
        private final GitStorageApi storage;
        private final Path directory;
        private final FilePackByteStore bytes;
        private final FilePackIndex index;

        private Attempt(GitStorageApi storage, Path directory, FilePackByteStore bytes, FilePackIndex index) {
            this.storage = storage;
            this.directory = directory;
            this.bytes = bytes;
            this.index = index;
        }

        @Override
        public FilePackByteStore bytes() {
            return bytes;
        }

        @Override
        public FilePackIndex index() {
            return index;
        }

        @Override
        public PackId commit(PackId receivedId) throws IOException {
            PackId id = complete(bytes, index, storage, receivedId);
            try (bytes; index) {
                // Close the finalized writers before moving either file.
            }
            publish(directory, id);
            discard(directory);
            return id;
        }

        @Override
        public void rollback() throws IOException {
            try (bytes; index) {
                // Release both handles even if closing either one fails.
            } catch (IOException | RuntimeException | Error failure) {
                closeFailed(() -> discard(directory), failure);
                throw failure;
            }
            discard(directory);
        }
    }

    static PackId complete(FilePackByteStore bytes, FilePackIndex index,
                           GitStorageApi storage, PackId receivedId) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(receivedId, "receivedId");
        try {
            if (index.hasUnresolved()) {
                throw new IOException("Pack contains unresolved objects");
            }
            long size = bytes.size();
            if (size < 32) {
                throw new IOException("Truncated received pack");
            }
            ByteBuffer header = ByteBuffer.wrap(readExactly(bytes, 0, 12));
            if (header.getInt() != 0x5041434b || header.getInt() != 2) {
                throw new IOException("Invalid received pack header");
            }
            long objectCount = Integer.toUnsignedLong(header.getInt());
            if (objectCount != index.entryCount()) {
                throw new IOException("Pack object count does not match its index");
            }
            if (objectCount != index.objectCount()) {
                throw new IOException("Pack contains duplicate objects");
            }
            byte[] checksum = digest(bytes, size - 20);
            if (!MessageDigest.isEqual(checksum, receivedId.toBytes())
                    || !MessageDigest.isEqual(checksum, readExactly(bytes, size - 20, 20))) {
                throw new IOException("Received pack checksum mismatch");
            }
            Optional<ObjectId> missing = index.nextExternalBase();
            PackId finalId = receivedId;
            if (missing.isPresent()) {
                bytes.truncate(size - 20);
                while (missing.isPresent()) {
                    if (objectCount == 0xffff_ffffL) {
                        throw new IOException("Completed pack exceeds the object count limit");
                    }
                    ObjectId base = missing.orElseThrow();
                    var entry = storage.readObject(base, new ResolvedGitObjectRead<>(storage,
                            (type, length, unused, content) -> appendBase(bytes, base, type, length, content)))
                            .orElseThrow(() -> new IOException("Missing external base: " + base));
                    index.addEntry(entry);
                    index.addObject(entry, base, entry.type(), entry.inflatedSize());
                    objectCount++;
                    missing = index.nextExternalBase();
                }
                bytes.rewrite(8, ByteBuffer.allocate(4).putInt((int) objectCount).flip());
                byte[] completedChecksum = digest(bytes, bytes.size());
                append(bytes, ByteBuffer.wrap(completedChecksum));
                finalId = new PackId(completedChecksum);
            }
            bytes.force();
            index.finish();
            return finalId;
        } catch (IOException | RuntimeException | Error failure) {
            closeFailed(index, failure);
            closeFailed(bytes, failure);
            throw failure;
        }
    }

    private static PackObjectParser.Entry appendBase(FilePackByteStore bytes, ObjectId expected,
            ObjectType type, long size, BufferedByteInput content) throws IOException {
        String name = switch (type) {
            case COMMIT -> "commit";
            case TREE -> "tree";
            case BLOB -> "blob";
            case TAG -> "tag";
            case OFS_DELTA, REF_DELTA -> throw new IOException("Pack completion requires restored base content");
        };
        if (size < 0) {
            throw new IOException("Negative base object size");
        }
        long offset = bytes.size();
        append(bytes, ByteBuffer.wrap(objectHeader(type, size)));
        long dataOffset = bytes.size();
        MessageDigest hash = sha1();
        hash.update((name + " " + size + "\0").getBytes(StandardCharsets.US_ASCII));
        ByteBuf buffer = Unpooled.buffer(8192, 8192);
        try (var zlib = new DeflaterOutputStream(new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                write(new byte[]{(byte) value}, 0, 1);
            }

            @Override
            public void write(byte[] data, int start, int count) throws IOException {
                append(bytes, ByteBuffer.wrap(data, start, count));
            }
        })) {
            long remaining = size;
            int count;
            while ((count = content.readInto(buffer, buffer.writableBytes())) != 0) {
                if (count > remaining) {
                    throw new IOException("Base content exceeds its declared size");
                }
                hash.update(buffer.array(), buffer.arrayOffset() + buffer.readerIndex(), count);
                zlib.write(buffer.array(), buffer.arrayOffset() + buffer.readerIndex(), count);
                remaining -= count;
                buffer.clear();
            }
            if (remaining != 0) {
                throw new EOFException("Truncated base content");
            }
            if (!MessageDigest.isEqual(hash.digest(), expected.toBytes())) {
                throw new IOException("External base ObjectId does not match its content");
            }
        } finally {
            buffer.release();
        }
        return new PackObjectParser.Entry(offset, dataOffset, size, type, OptionalLong.empty(), Optional.empty());
    }

    private static byte[] objectHeader(ObjectType type, long size) {
        byte[] header = new byte[10];
        int count = 0;
        int part = type.code() << 4 | (int) (size & 15);
        size >>>= 4;
        while (size != 0) {
            header[count++] = (byte) (part | 128);
            part = (int) (size & 127);
            size >>>= 7;
        }
        header[count++] = (byte) part;
        return Arrays.copyOf(header, count);
    }

    private static void append(FilePackByteStore bytes, ByteBuffer source) throws IOException {
        while (source.hasRemaining()) {
            bytes.write(source);
        }
    }

    private static byte[] readExactly(FilePackByteStore bytes, long offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            int count = bytes.read(offset, buffer);
            if (count < 0) {
                throw new EOFException("Truncated pack file");
            }
            offset += count;
        }
        return buffer.array();
    }

    private static byte[] digest(FilePackByteStore bytes, long length) throws IOException {
        MessageDigest hash = sha1();
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        long position = 0;
        while (position < length) {
            buffer.clear().limit((int) Math.min(buffer.capacity(), length - position));
            int count = bytes.read(position, buffer);
            if (count < 0) {
                throw new EOFException("Truncated pack file during checksum calculation");
            }
            hash.update(buffer.array(), 0, count);
            position += count;
        }
        return hash.digest();
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-1 is required for Git packs", error);
        }
    }

    private static void closeFailed(AutoCloseable resource, Throwable failure) {
        try {
            resource.close();
        } catch (Throwable cleanup) {
            if (cleanup != failure) {
                failure.addSuppressed(cleanup);
            }
        }
    }
}
