package pro.deta.orion.git.parser.v2.data;

import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.pack.PackByteStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;

/**
 * Direct access to an entry's original zlib stream, without decompression, hashing, or delta application.
 * type describes its packed representation and size is the inflated payload length, as in GitObjectRead.
 * compressedSize bounds the raw stream starting at dataOffset in the borrowed PackByteStore. Pack entry
 * headers and base references are excluded. baseId is empty for full objects and identifies the base for
 * either delta encoding; storage translates an original OFS_DELTA base offset to its ObjectId.
 *
 * <p>read addresses compressed-stream-relative offsets and must not cross compressedSize. It accepts any
 * writable ByteBuffer, advances position, preserves limit, and retains no caller buffer. Partial reads are
 * allowed; an empty destination returns zero, otherwise a read returns a positive count or -1 at the stream
 * end. Negative offsets fail with IllegalArgumentException, null buffers with NullPointerException, and
 * read-only buffers with ReadOnlyBufferException. Truncated backing data is IOException, not normal EOF.
 * Writer can reuse these bytes and construct its own entry header and base reference.
 *
 * <p>close releases this read handle only, never the borrowed store. It is idempotent; reads after close fail
 * with ClosedChannelException. Callers close handles before their owning upload or storage resources.
 * Fields describe the raw slice; reading and cleanup remain placeholders. No inflater belongs to this class.
 */
public final class RawGitObjectRead implements GitObjectRead {
    private final ObjectType type;
    private final long size;
    private final PackByteStore byteStore;
    private final long dataOffset;
    private final long compressedSize;
    private final Optional<ObjectId> baseId;

    public RawGitObjectRead(ObjectType type, long size, PackByteStore byteStore, long dataOffset,
                            long compressedSize, Optional<ObjectId> baseId) {
        this.type = Objects.requireNonNull(type, "type");
        this.size = size;
        this.byteStore = Objects.requireNonNull(byteStore, "byteStore");
        this.dataOffset = dataOffset;
        this.compressedSize = compressedSize;
        this.baseId = Objects.requireNonNull(baseId, "baseId");
    }

    @Override
    public ObjectType type() {
        return type;
    }

    @Override
    public long size() {
        return size;
    }

    public long compressedSize() {
        return compressedSize;
    }

    public Optional<ObjectId> baseId() {
        return baseId;
    }

    public int read(long offset, ByteBuffer destination) throws IOException {
        throw new UnsupportedOperationException("Raw object reads are not implemented");
    }

    @Override
    public void close() throws IOException {
        throw new UnsupportedOperationException("Raw object read cleanup is not implemented");
    }
}
