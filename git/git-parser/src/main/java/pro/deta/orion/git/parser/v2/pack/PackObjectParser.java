package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Stateless parsing of one physical pack entry with a caller-selected payload processor.
 * parseEntry consumes the header and complete zlib stream, validating the encoding and inflated length.
 * It calls reader with physical type, declared inflated size, and a bounded borrowed zlib source; Result
 * contains the returned value alongside Entry metadata. No reader or returned value may retain that source.
 * HashedGitObjectRead computes IDs for full objects; ContentGitObjectRead processes inflated content or
 * delta instructions. The parser never fetches bases, applies deltas, or hashes instructions as an ObjectId.
 *
 * <p>Original header, base-reference, and compressed bytes are appended to the borrowed PackByteStore once.
 * The caller has already retained the pack prefix up to offset. Parsing uses bounded working buffers;
 * compressed length is absent from the header, so the stream must be traversed to locate its end.
 * The provider drains and validates payload not consumed by reader before reporting success. Prefetched
 * bytes after the boundary remain available through the same buffered input and are not appended.
 * Sink writes complete before buffers are reused. Neither source nor byteStore is closed.
 * Failures and malformed or truncated data are IOException; partial raw writes require caller rollback.
 * A resource-bearing result requires cleanup if validation fails before it can be returned to its caller.
 *
 * <p>PackUpload uses Optional<ObjectId> as the value: a full object's hash or empty for a delta whose original
 * bytes remain available for resolver reads. The index retains metadata, never processors or live read handles.
 * Other callers can select their own result and own any resources it contains. Pack header, count, checksum,
 * and index state belong to the caller. ZlibBoundaryInputStream owns compressed stream validation and
 * boundary detection; the parser buffers retained writes separately. readStored applies the same bounded
 * payload validation to indexed bytes without changing their borrowed store or reading transport input.
 */
public final class PackObjectParser {
    private PackObjectParser() {
    }

    public static <R> Result<R> parseEntry(BufferedByteInput source, long offset, PackByteStore byteStore,
                                         GitObjectRead<R> reader)
            throws IOException {
        Objects.requireNonNull(reader, "reader");
        if (offset < 12) {
            throw new IllegalArgumentException("Entry offset must follow the pack header");
        }
        var input = new RetainedInput(source, offset, byteStore);
        int first = input.read();
        ObjectType type = switch ((first >>> 4) & 7) {
            case 1 -> ObjectType.COMMIT;
            case 2 -> ObjectType.TREE;
            case 3 -> ObjectType.BLOB;
            case 4 -> ObjectType.TAG;
            case 6 -> ObjectType.OFS_DELTA;
            case 7 -> ObjectType.REF_DELTA;
            default -> throw new IOException("Invalid pack object type");
        };
        long size = first & 15;
        int part = first;
        int shift = 4;
        while ((part & 128) != 0) {
            part = input.read();
            if (shift > 60 || shift == 60 && (part & 127) > 7) {
                throw new IOException("Pack object size overflows a signed long");
            }
            size |= (long) (part & 127) << shift;
            shift += 7;
        }
        OptionalLong baseOffset = OptionalLong.empty();
        Optional<ObjectId> baseId = Optional.empty();
        if (type == ObjectType.OFS_DELTA) {
            part = input.read();
            long distance = part & 127;
            while ((part & 128) != 0) {
                part = input.read();
                if (distance >= (Long.MAX_VALUE >>> 7)) {
                    throw new IOException("Pack delta offset overflows a signed long");
                }
                distance = ((distance + 1) << 7) | (part & 127);
            }
            if (distance == 0 || distance > offset - 12) {
                throw new IOException("Pack delta base must precede the entry and follow the pack header");
            }
            baseOffset = OptionalLong.of(offset - distance);
        } else if (type == ObjectType.REF_DELTA) {
            byte[] id = new byte[20];
            for (int i = 0; i < id.length; i++) {
                id[i] = (byte) input.read();
            }
            baseId = Optional.of(new ObjectId(id));
        }
        var entry = new Entry(offset, input.offset, size, type, baseOffset, baseId);
        R value = null;
        try (var zlib = new ZlibBoundaryInputStream(input, size)) {
            var raw = new InputStreamBufferedByteInput(zlib);
            value = Objects.requireNonNull(reader.read(type, size, baseId, raw), "reader result");
            byte[] discard = new byte[8192];
            while (zlib.read(discard) != -1) {
                // Finish validating and retaining the caller's unread payload.
            }
            input.flush();
            return new Result<>(entry, value);
        } catch (IOException | RuntimeException | Error failure) {
            if (value instanceof AutoCloseable resource) {
                try {
                    resource.close();
                } catch (Throwable cleanup) {
                    if (cleanup != failure) {
                        failure.addSuppressed(cleanup);
                    }
                }
            }
            throw failure;
        }
    }

    public static <R> R readStored(Entry entry, PackByteStore byteStore, long end,
                                   GitObjectRead<R> reader) throws IOException {
        Objects.requireNonNull(reader, "reader");
        R value = null;
        try (var zlib = new ZlibBoundaryInputStream(new StoredInput(byteStore, entry.dataOffset(), end),
                entry.inflatedSize())) {
            value = Objects.requireNonNull(reader.read(entry.type(), entry.inflatedSize(), entry.baseId(),
                    new InputStreamBufferedByteInput(zlib)), "reader result");
            byte[] discard = new byte[8192];
            while (zlib.read(discard) != -1) {
                // Validate unread payload before returning the result.
            }
            return value;
        } catch (IOException | RuntimeException | Error failure) {
            if (value instanceof AutoCloseable resource) {
                try {
                    resource.close();
                } catch (Throwable cleanup) {
                    if (cleanup != failure) {
                        failure.addSuppressed(cleanup);
                    }
                }
            }
            throw failure;
        }
    }

    private static final class StoredInput extends InputStream {
        private final ByteBuffer buffer = ByteBuffer.allocate(8192);
        private final PackByteStore byteStore;
        private final long end;
        private long position;

        private StoredInput(PackByteStore byteStore, long position, long end) {
            this.byteStore = byteStore;
            this.position = position;
            this.end = end;
            buffer.limit(0);
        }

        @Override
        public int read() throws IOException {
            if (!buffer.hasRemaining()) {
                if (position >= end) {
                    return -1;
                }
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), end - position));
                int count = byteStore.read(position, buffer);
                if (count == -1) {
                    return -1;
                }
                if (count == 0) {
                    throw new IOException("Pack byte store made no read progress");
                }
                position += count;
                buffer.flip();
            }
            return buffer.get() & 255;
        }
    }

    private static final class RetainedInput extends InputStream {
        private final BufferedByteInput source;
        private final PackByteStore store;
        private final ByteBuffer pending = ByteBuffer.allocate(8192);
        private long offset;

        private RetainedInput(BufferedByteInput source, long offset, PackByteStore store) {
            this.source = Objects.requireNonNull(source, "source");
            this.store = Objects.requireNonNull(store, "byteStore");
            this.offset = offset;
        }

        @Override
        public int read() throws IOException {
            if (offset == Long.MAX_VALUE) {
                throw new IOException("Pack offset overflows a signed long");
            }
            int value = source.readUnsignedByte();
            pending.put((byte) value);
            offset++;
            if (!pending.hasRemaining()) {
                flush();
            }
            return value;
        }

        private void flush() throws IOException {
            pending.flip();
            while (pending.hasRemaining()) {
                if (store.write(pending) <= 0) {
                    throw new IOException("Pack byte store made no write progress");
                }
            }
            pending.clear();
        }
    }

    /**
     * Physical metadata without live content handles or a resolved ObjectId. offset locates the entry header;
     * dataOffset locates its zlib stream, both absolute pack offsets. inflatedSize counts full content
     * bytes or delta instructions. OFS_DELTA has only baseOffset, decoded to an absolute earlier entry offset;
     * REF_DELTA has only baseId, which may refer inside or outside the pack. Full entries have neither field.
     * Parsing validates these combinations and boundaries before returning metadata.
     */
    public record Entry(long offset, long dataOffset, long inflatedSize, ObjectType type,
                        OptionalLong baseOffset, Optional<ObjectId> baseId) {
    }

    /**
     * Physical metadata and the nonnull value returned by the payload processor. Resource ownership belongs
     * to the caller only after successful parsing. Entry can be indexed independently of the transient value.
     */
    public record Result<R>(Entry entry, R value) {
    }
}
