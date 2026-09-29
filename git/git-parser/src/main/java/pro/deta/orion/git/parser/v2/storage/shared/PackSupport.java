package pro.deta.orion.git.parser.v2.storage.shared;

import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.pack.PackEntry;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class PackSupport {
    private PackSupport() {}

    public static <R> R readBounded(PackDataStorage bytes, PackEntry entry, long end,
                                     Optional<ObjectId> baseId, GitObjectRead<R> reader) throws IOException {
        if (entry.dataOffset() <= entry.offset() || entry.offset() < 12
                || end <= entry.dataOffset() || end > bytes.size() - 20) {
            throw new EOFException("Invalid stored object boundary");
        }
        GitObjectType type = entry.type() == GitObjectType.OFS_DELTA
                ? GitObjectType.REF_DELTA : entry.type();
        R value = null;
        try (BufferedByteInputV2 input = new BufferedByteInputV2(
                new PackByteSource(bytes, entry.dataOffset(), end))) {
            value = Objects.requireNonNull(reader.read(type, entry.inflatedSize(), baseId, input),
                    "reader result");
            return value;
        } catch (IOException | RuntimeException | Error error) {
            closeUnreturned(value, error);
            throw error;
        }
    }

    public static <R> R readStored(PackEntry entry, PackDataStorage byteStore, long end,
                                   GitObjectRead<R> reader) throws IOException {
        Objects.requireNonNull(reader, "reader");
        if (end < entry.dataOffset()) {
            throw new EOFException("Truncated stored object");
        }
        R value = null;
        try (BufferedByteInputV2 raw = new BufferedByteInputV2(new PackByteSource(byteStore, entry.dataOffset(), end));
             BufferedByteInputV2 input = new BufferedByteInputV2(new ZlibBoundaryByteSource(raw, entry.inflatedSize()))) {
            value = Objects.requireNonNull(reader.read(entry.type(), entry.inflatedSize(), entry.baseId(),
                    input), "reader result");
            ByteBuffer remaining;
            while ((remaining = input.buffer()) != null) {
                remaining.position(remaining.limit());
            }
            return value;
        } catch (IOException | RuntimeException | Error failure) {
            closeUnreturned(value, failure);
            throw failure;
        }
    }

    public static void closeUnreturned(Object value, Throwable failure) {
        if (value instanceof AutoCloseable resource) {
            try {
                resource.close();
            } catch (Throwable cleanup) {
                if (cleanup != failure) {
                    failure.addSuppressed(cleanup);
                }
            }
        }
    }

    public static void validateEntry(PackEntry entry) throws IOException {
        if (entry.offset() < 12 || entry.dataOffset() <= entry.offset() || entry.inflatedSize() < 0
                || entry.type() == null || entry.baseOffset() == null || entry.baseId() == null) {
            throw new IOException("Invalid physical pack entry metadata");
        }
        boolean valid = switch (entry.type()) {
            case OFS_DELTA -> entry.baseId().isEmpty() && entry.baseOffset().isPresent()
                    && entry.baseOffset().getAsLong() >= 12 && entry.baseOffset().getAsLong() < entry.offset()
                    && entry.dataOffset() - entry.offset() >= 2;
            case REF_DELTA -> entry.baseOffset().isEmpty() && entry.baseId().isPresent()
                    && entry.dataOffset() - entry.offset() >= 21;
            case COMMIT, TREE, BLOB, TAG -> entry.baseId().isEmpty() && entry.baseOffset().isEmpty();
        };
        if (!valid) {
            throw new IOException("Invalid pack entry base reference");
        }
    }

    public static void validateObject(PackEntry entry, GitObjectType type, long size)
            throws IOException {
        if (size < 0 || type == GitObjectType.OFS_DELTA || type == GitObjectType.REF_DELTA) {
            throw new IOException("Object completion requires a logical type and non-negative size");
        }
        if (entry.type() != GitObjectType.OFS_DELTA && entry.type() != GitObjectType.REF_DELTA
                && (type != entry.type() || size != entry.inflatedSize())) {
            throw new IOException("Full object metadata differs from its physical pack entry");
        }
    }

    public static PackId checksum(PackDataStorage bytes) throws IOException {
        long size = bytes.size();
        if (size < 32) {
            throw new EOFException("Truncated pack file");
        }
        ByteBuffer trailer = ByteBuffer.allocate(20);
        while (trailer.hasRemaining()) {
            int count = bytes.read(size - 20 + trailer.position(), trailer);
            if (count <= 0) {
                throw new EOFException("Truncated pack checksum");
            }
        }
        return new PackId(trailer.array());
    }

    public static byte[] digest(PackDataStorage bytes, long length) throws IOException {
        MessageDigest hash = GitHashAlgorithm.SHA1.newDigest();
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

    public static void inspectChain(IndexedPack data, Map<Long, Long> visited,
                                    long start, Runnable changed) throws IOException {
        if (visited.containsKey(start)) {
            return;
        }
        long offset = start;
        while (true) {
            Long previous = visited.get(offset);
            if (previous != null) {
                if (previous == start) {
                    throw new IOException("Pack index contains a delta cycle requiring an external base");
                }
                return;
            }
            IndexedPack.Record record = data.record(offset);
            if (record == null || record.objectId() == null) {
                throw new IOException("Pack index contains a missing or unresolved base record");
            }
            visited.put(offset, start);
            changed.run();
            PackEntry entry = record.entry();
            if (entry.baseId().isPresent()) {
                ObjectId base = entry.baseId().orElseThrow();
                Long baseOffset = data.objectOffset(base);
                if (baseOffset == null) {
                    throw new IOException("Pack still requires an external base");
                }
                offset = baseOffset;
            } else if (entry.baseOffset().isPresent()) {
                offset = entry.baseOffset().getAsLong();
            } else {
                return;
            }
        }
    }
}
