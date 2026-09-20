package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.read.ContentGitObjectRead;
import pro.deta.orion.git.parser.v2.read.DeltaByteSource;
import pro.deta.orion.git.parser.v2.read.ResolvedGitObjectRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.zip.Deflater;

public final class GitPackObjectResolver {
    private final IndexedPack pack;
    private final GitStorageApi storage;

    public GitPackObjectResolver(IndexedPack pack, GitStorageApi storage) {
        this.pack = Objects.requireNonNull(pack, "pack");
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public PackId complete() throws IOException {
        pack.requireMutable();
        try (PackUploadIndex index = PackUploadIndex.create(pack)) {
            resolve(index);
            return complete(pack, index, storage);
        } catch (IOException | RuntimeException | Error failure) {
            closeFailed(pack, failure);
            throw failure;
        }
    }

    private void resolve(PackUploadIndex index) throws IOException {
        Iterator<Long> offsets = pack.offsets();
        while (offsets.hasNext()) {
            IndexedPack.Record record = pack.record(offsets.next());
            if (record.objectId() == null) {
                record = resolve(record.entry(), index);
            }
            if (record != null) {
                resolveWaiting(record, index);
            }
        }
    }

    private void resolveWaiting(IndexedPack.Record available, PackUploadIndex index) throws IOException {
        Deque<IndexedPack.Record> pending = new ArrayDeque<>();
        pending.push(available);
        while (!pending.isEmpty()) {
            IndexedPack.Record base = pending.peek();
            Optional<IndexedPack.EntryMetadata> waiting = index.waitingFor(base.objectId(), base.entry().offset());
            if (waiting.isEmpty()) {
                pending.pop();
            } else {
                IndexedPack.Record resolved = resolve(waiting.orElseThrow(), index);
                if (resolved == null) {
                    throw new IOException("Indexed delta base is unavailable");
                }
                pending.push(resolved);
            }
        }
    }

    private IndexedPack.Record resolve(IndexedPack.EntryMetadata entry, PackUploadIndex index) throws IOException {
        IndexedPack.Record base = baseRecord(entry);
        IndexedPack.Record resolved;
        if (base != null && base.objectId() != null) {
            resolved = resolve(entry, base.type(), readContent(base.entry()));
        } else if (entry.baseId().isPresent()) {
            resolved = storage.readObject(entry.baseId().orElseThrow(), new ResolvedGitObjectRead<>(storage,
                    (type, size, unused, content) -> resolve(entry, type, readBytes(content, size))))
                    .orElse(null);
        } else {
            return null;
        }
        if (resolved != null) {
            index.addObject(entry, resolved.objectId(), resolved.type(), resolved.size());
        }
        return resolved;
    }

    private IndexedPack.Record baseRecord(IndexedPack.EntryMetadata entry) throws IOException {
        if (entry.baseOffset().isPresent()) {
            return pack.record(entry.baseOffset().getAsLong());
        }
        Long offset = entry.baseId().map(pack::objectOffset).orElse(null);
        return offset == null ? null : pack.record(offset);
    }

    private byte[] readContent(IndexedPack.EntryMetadata entry) throws IOException {
        Deque<IndexedPack.EntryMetadata> deltas = new ArrayDeque<>();
        byte[] content;
        while (true) {
            if (entry.baseId().isEmpty() && entry.baseOffset().isEmpty()) {
                content = pack.readObject(entry.offset(),
                        new ContentGitObjectRead<>((type, size, unused, input) -> readBytes(input, size)));
                break;
            }
            if (deltas.size() >= pack.entryCount()) {
                throw new IOException("Cyclic delta bases in pack");
            }
            deltas.push(entry);
            IndexedPack.Record base = baseRecord(entry);
            if (base == null) {
                ObjectId id = entry.baseId().orElseThrow(() -> new IOException("Missing offset delta base"));
                content = storage.readObject(id, new ResolvedGitObjectRead<>(storage,
                        (type, size, unused, input) -> readBytes(input, size)))
                        .orElseThrow(() -> new IOException("Missing delta base: " + id));
                break;
            }
            entry = base.entry();
        }
        while (!deltas.isEmpty()) {
            byte[] base = content;
            content = pack.readObject(deltas.pop().offset(), new ContentGitObjectRead<>((type, size, unused, input) -> {
                DeltaByteSource delta = new DeltaByteSource(input, base);
                try (BufferedByteInputV2 restored = new BufferedByteInputV2(delta)) {
                    byte[] result = readBytes(restored, delta.size());
                    if (restored.buffer() != null) {
                        throw new IOException("Delta content exceeds its declared size");
                    }
                    return result;
                }
            }));
        }
        return content;
    }

    private IndexedPack.Record resolve(IndexedPack.EntryMetadata entry, GitObjectType type, byte[] base)
            throws IOException {
        return pack.readObject(entry.offset(), new ContentGitObjectRead<>((physicalType, size, unused, input) -> {
            DeltaByteSource delta = new DeltaByteSource(input, base);
            try (BufferedByteInputV2 restored = new BufferedByteInputV2(delta)) {
                MessageDigest hash = GitHashAlgorithm.SHA1.newDigest();
                hash.update((type.name().toLowerCase(Locale.ROOT) + " " + delta.size() + "\0")
                        .getBytes(StandardCharsets.US_ASCII));
                ByteBuffer buffer;
                while ((buffer = restored.buffer()) != null) {
                    hash.update(buffer);
                }
                return new IndexedPack.Record(entry, new ObjectId(hash.digest()), type, delta.size());
            }
        }));
    }

    private static byte[] readBytes(BufferedByteInputV2 input, long size) throws IOException {
        if (size > Integer.MAX_VALUE - 8) {
            throw new IOException("Delta base is too large for in-memory resolution");
        }
        return input.readBytes((int) size);
    }

    private static PackId complete(IndexedPack bytes, PackUploadIndex index,
                                   GitStorageApi storage) throws IOException {
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
        if (objectCount != bytes.entryCount()) {
            throw new IOException("Pack object count does not match its index");
        }
        if (objectCount != bytes.objectCount()) {
            throw new IOException("Pack contains duplicate objects");
        }
        Optional<ObjectId> missing = index.nextExternalBase();
        long dataEnd = size - 20;
        if (missing.isPresent()) {
            bytes.truncate(size - 20);
            while (missing.isPresent()) {
                if (objectCount == 0xffff_ffffL) {
                    throw new IOException("Completed pack exceeds the object count limit");
                }
                ObjectId base = missing.orElseThrow();
                IndexedPack.EntryMetadata entry = storage.readObject(base, new ResolvedGitObjectRead<>(storage,
                        (type, length, unused, content) -> appendBase(bytes, base, type, length, content)))
                        .orElseThrow(() -> new IOException("Missing external base: " + base));
                index.addEntry(entry);
                index.addObject(entry, base, entry.type(), entry.inflatedSize());
                objectCount++;
                missing = index.nextExternalBase();
            }
            ByteBuffer count = ByteBuffer.allocate(4).putInt((int) objectCount).flip();
            bytes.write(8, count);
            dataEnd = bytes.size();
        }
        PackId id = bytes.finish(dataEnd);
        index.finish();
        return id;
    }

    private static IndexedPack.EntryMetadata appendBase(IndexedPack bytes, ObjectId expected,
            GitObjectType type, long size, BufferedByteInputV2 content) throws IOException {
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
        long dataOffset;
        MessageDigest hash = GitHashAlgorithm.SHA1.newDigest();
        hash.update((name + " " + size + "\0").getBytes(StandardCharsets.US_ASCII));
        byte[] compressed = new byte[8192];
        Deflater deflater = new Deflater();
        try {
            PackEntryWriter entry = (buffer, start, length) -> bytes.append(ByteBuffer.wrap(buffer, start, length));
            dataOffset = offset + entry.writeObject(type, size, content, deflater, compressed, hash::update);
            if (!MessageDigest.isEqual(hash.digest(), expected.toBytes())) {
                throw new IOException("External base ObjectId does not match its content");
            }
        } finally {
            deflater.end();
        }
        return new IndexedPack.EntryMetadata(offset, dataOffset, size, type, OptionalLong.empty(), Optional.empty());
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
