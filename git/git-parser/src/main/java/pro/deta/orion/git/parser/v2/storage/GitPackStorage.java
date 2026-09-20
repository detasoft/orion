package pro.deta.orion.git.parser.v2.storage;

import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.git.parser.v2.read.GitPackRead;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class GitPackStorage {
    private final Path packs;
    private final Path incoming;
    private final GitLock lock;
    private final Map<PackId, IndexedPack> memory;

    GitPackStorage(Path repository) throws IOException {
        memory = null;
        Path root = repository.toRealPath();
        packs = root.resolve("packs");
        incoming = root.resolve("incoming");
        Files.createDirectories(packs);
        Files.createDirectories(incoming);
        forceDirectory(root);
        lock = new GitLock(root);
    }

    GitPackStorage() {
        packs = null;
        incoming = null;
        lock = new GitLock(this);
        memory = new ConcurrentHashMap<>();
    }

    void close() throws IOException {
        if (memory != null) {
            for (IndexedPack pack : memory.values()) {
                pack.discard();
            }
            memory.clear();
        }
    }

    List<PackId> ids() throws IOException {
        if (memory != null) {
            return List.copyOf(memory.keySet());
        }
        List<PackId> ids = new ArrayList<>();
        scan((id, path) -> {
            ids.add(id);
            return Optional.empty();
        });
        return List.copyOf(ids);
    }

    <R> Optional<R> readPack(PackId id, GitPackRead<R> reader) throws IOException {
        if (memory != null) {
            IndexedPack pack = memory.get(id);
            return pack == null ? Optional.empty() : readPack(pack.size(), pack.input(), reader);
        }
        Path path = packPath(id);
        Path index = path.resolveSibling(id.toHex().substring(2) + ".mv");
        if (!Files.exists(index)) {
            return Optional.empty();
        }
        long size = Files.size(path);
        return readPack(size, new BufferedByteInputV2(Files.newInputStream(path)), reader);
    }

    private static <R> Optional<R> readPack(long size, BufferedByteInputV2 input, GitPackRead<R> reader)
            throws IOException {
        R value = null;
        try (input) {
            value = Objects.requireNonNull(reader.read(size, input), "reader result");
            return Optional.of(value);
        } catch (IOException | RuntimeException | Error failure) {
            if (value instanceof AutoCloseable resource) {
                closeFailed(resource, failure);
            }
            throw failure;
        }
    }

    IndexedPack createPack() throws IOException {
        if (memory != null) {
            return IndexedPack.create();
        }
        return IndexedPack.create(incoming.resolve("pack-" + UUID.randomUUID()));
    }

    PackId persist(IndexedPack pack) throws IOException {
        if (memory != null) {
            try {
                PackId id = pack.id();
                IndexedPack existing = memory.putIfAbsent(id, pack);
                if (existing != null && existing != pack) {
                    pack.discard();
                }
                return id;
            } catch (IOException | RuntimeException | Error failure) {
                closeFailed(pack::discard, failure);
                throw failure;
            }
        }
        if (pack.isInMemory()) {
            try {
                pack.id();
                IndexedPack staged = pack.copyTo(incoming.resolve("pack-" + UUID.randomUUID()));
                PackId id = persist(staged);
                pack.close();
                return id;
            } catch (IOException | RuntimeException | Error failure) {
                closeFailed(pack::discard, failure);
                throw failure;
            }
        }
        Path directory = pack.directory();
        try {
            PackId id = pack.id();
            pack.close();
            publish(directory, id);
            pack.discard();
            return id;
        } catch (IOException | RuntimeException | Error failure) {
            closeFailed(pack::discard, failure);
            throw failure;
        }
    }

    <R> Optional<R> read(ObjectId id, GitObjectRead<R> reader) throws IOException {
        List<PackObjectLocation> locations = locate(List.of(id));
        return locations.isEmpty() ? Optional.empty() : Optional.of(read(locations.getFirst(), reader));
    }

    <R> R read(PackObjectLocation location, GitObjectRead<R> reader) throws IOException {
        if (memory != null) {
            IndexedPack pack = memory.get(location.packId());
            if (pack == null) {
                throw new IOException("Missing source pack: " + location.packId());
            }
            return pack.readObject(location.entry(), location.end(), location.baseId(), reader);
        }
        return IndexedPack.readObject(packPath(location.packId()), location.entry(),
                location.end(), location.baseId(), reader);
    }

    List<PackObjectLocation> locate(Collection<ObjectId> ids) throws IOException {
        Map<ObjectId, PackObjectLocation> found = new LinkedHashMap<>();
        for (ObjectId id : ids) {
            found.put(Objects.requireNonNull(id, "objectId"), null);
        }
        if (found.isEmpty()) {
            return List.of();
        }
        if (memory != null) {
            for (Map.Entry<PackId, IndexedPack> pack : memory.entrySet()) {
                if (locate(pack.getKey(), pack.getValue(), found)) {
                    break;
                }
            }
        } else {
            scan((packId, path) -> {
                try (GitLock.Lease lease = lockPack(packId);
                     IndexedPack index = IndexedPack.open(packPath(packId), path)) {
                    if (!index.id().equals(packId)) {
                        throw new IOException("Stored pack checksum does not match its identity");
                    }
                    return locate(packId, index, found) ? Optional.of(true) : Optional.empty();
                }
            });
        }
        List<PackObjectLocation> result = new ArrayList<>(found.size());
        for (PackObjectLocation location : found.values()) {
            if (location != null) {
                result.add(location);
            }
        }
        return List.copyOf(result);
    }

    private static boolean locate(PackId packId, IndexedPack pack,
                                  Map<ObjectId, PackObjectLocation> found) throws IOException {
        boolean complete = true;
        for (Map.Entry<ObjectId, PackObjectLocation> requested : found.entrySet()) {
            if (requested.getValue() != null) {
                continue;
            }
            Optional<IndexedPack.EntryMetadata> candidate = pack.find(requested.getKey());
            if (candidate.isEmpty()) {
                complete = false;
                continue;
            }
            IndexedPack.EntryMetadata entry = candidate.orElseThrow();
            requested.setValue(new PackObjectLocation(requested.getKey(), packId, entry,
                    pack.dataEnd(entry.offset()), pack.baseId(entry.offset())));
        }
        return complete;
    }

    Set<ObjectId> objectIds(PackId id) throws IOException {
        if (memory != null) {
            IndexedPack pack = memory.get(id);
            return pack == null ? Set.of() : pack.objectIds();
        }
        try (GitLock.Lease lease = lockPack(id)) {
            Path path = packPath(id);
            Path index = path.resolveSibling(id.toHex().substring(2) + ".mv");
            if (!Files.exists(index)) {
                return Set.of();
            }
            try (IndexedPack pack = IndexedPack.open(path, index)) {
                return pack.objectIds();
            }
        }
    }

    Map<ObjectId, List<PackId>> find(Collection<ObjectId> ids) throws IOException {
        Map<ObjectId, List<PackId>> result = new LinkedHashMap<>();
        for (ObjectId id : ids) {
            Objects.requireNonNull(id, "objectId");
        }
        if (memory != null) {
            for (Map.Entry<PackId, IndexedPack> pack : memory.entrySet()) {
                for (ObjectId id : ids) {
                    if (pack.getValue().find(id).isPresent()) {
                        result.computeIfAbsent(id, ignored -> new ArrayList<>()).add(pack.getKey());
                    }
                }
            }
            return result;
        }
        scan((packId, path) -> {
            try (GitLock.Lease lease = lockPack(packId); IndexedPack index = IndexedPack.open(packPath(packId), path)) {
                for (ObjectId id : ids) {
                    if (index.find(id).isPresent()) {
                        List<PackId> locations = result.computeIfAbsent(id, ignored -> new ArrayList<>());
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
        try (DirectoryStream<Path> shards = Files.newDirectoryStream(packs, "[0-9a-f][0-9a-f]")) {
            for (Path shard : shards) {
                try (DirectoryStream<Path> indexes = Files.newDirectoryStream(shard, "*.mv")) {
                    for (Path index : indexes) {
                        String name = index.getFileName().toString();
                        if (!name.matches("[0-9a-f]{38}\\.mv")) {
                            throw new IOException("Invalid published pack index name: " + index);
                        }
                        PackId id = new PackId(shard.getFileName() + name.substring(0, 38));
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

    void publish(Path directory, PackId id) throws IOException {
        try (GitLock.Lease lease = lockPack(id)) {
            Path target = packPath(id);
            Path shard = target.getParent();
            Path index = shard.resolve(id.toHex().substring(2) + ".mv");
            Files.createDirectories(shard);
            forceDirectory(packs);
            if (Files.exists(index)) {
                try (IndexedPack bytes = IndexedPack.open(target, index)) {
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

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private interface PublishedIndexRead<R> {
        Optional<R> read(PackId id, Path index) throws IOException;
    }

    private static byte[] readExactly(IndexedPack bytes, long offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            int count = bytes.read(offset, buffer);
            if (count < 0) {
                throw new EOFException("Truncated pack file");
            }
            if (count == 0) {
                throw new IOException("Pack file read made no progress");
            }
            offset += count;
        }
        return buffer.array();
    }

    private static byte[] digest(IndexedPack bytes, long length) throws IOException {
        MessageDigest hash = sha1();
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        long position = 0;
        while (position < length) {
            buffer.clear().limit((int) Math.min(buffer.capacity(), length - position));
            int count = bytes.read(position, buffer);
            if (count < 0) {
                throw new EOFException("Truncated pack file during checksum calculation");
            }
            if (count == 0) {
                throw new IOException("Pack file read made no progress");
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

    static void closeFailed(AutoCloseable resource, Throwable failure) {
        try {
            resource.close();
        } catch (Throwable cleanup) {
            if (cleanup != failure) {
                failure.addSuppressed(cleanup);
            }
        }
    }
}
