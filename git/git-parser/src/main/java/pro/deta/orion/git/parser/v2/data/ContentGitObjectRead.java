package pro.deta.orion.git.parser.v2.data;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Provides positional reads of inflated payload without requiring whole-object buffering in memory.
 * During initial pack parsing, OFS_DELTA and REF_DELTA results expose instructions for the current reconstruction.
 * Inflated or restored bytes need only survive their active use or open handle; they are not retained for
 * another entry's future use. PackUpload.readObject reopens original pack bytes by offset when content is
 * needed again. Any required decompression and delta reconstruction are repeated; no consumer counting or
 * cross-entry content cache is required at this stage. Content is not separately persisted alongside the pack.
 * GitStorageApi.readObject and GitPackObjectResolver.getObject return restored content with COMMIT, TREE,
 * BLOB, or TAG type. Only the resolver applies delta instructions; reading a delta payload does not restore it.
 * type, nonnegative size, and payload remain stable while open. Read offsets address inflated bytes, excluding
 * compression and pack or loose-object headers; these differ from the pack offset used to open the handle.
 *
 * <p>read accepts writable heap, direct, and sliced ByteBuffers, advances position, preserves limit, and
 * retains no caller buffer. Partial reads are allowed; an empty destination returns zero, otherwise a read
 * returns a positive count or -1 at or beyond size. Negative offsets fail with IllegalArgumentException,
 * null buffers with NullPointerException, and read-only buffers with ReadOnlyBufferException.
 * No shared cursor or concurrent-use guarantee is required. Positional access promises neither zero-copy
 * nor constant-time access to compressed backing data. It need not materialize the whole payload to read it.
 *
 * <p>close releases this handle's resources and is idempotent; reads afterward fail with ClosedChannelException.
 * It never closes the owning upload, repository, or transport. Callers close handles before their provider.
 * Constructor fields describe the payload; reading, backing storage, and cleanup remain unimplemented.
 */
public final class ContentGitObjectRead implements GitObjectRead {
    private final ObjectType type;
    private final long size;

    public ContentGitObjectRead(ObjectType type, long size) {
        this.type = Objects.requireNonNull(type, "type");
        this.size = size;
    }

    @Override
    public ObjectType type() {
        return type;
    }

    @Override
    public long size() {
        return size;
    }

    public int read(long offset, ByteBuffer destination) throws IOException {
        throw new UnsupportedOperationException("Object content reads are not implemented");
    }

    @Override
    public void close() throws IOException {
        throw new UnsupportedOperationException("Object content cleanup is not implemented");
    }
}
