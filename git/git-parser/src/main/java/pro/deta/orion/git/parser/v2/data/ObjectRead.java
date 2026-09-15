package pro.deta.orion.git.parser.v2.data;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Provides positional access to restored object content obtained through GitStorageApi.
 * The owning command closes this handle to release storage resources, including after partial reads or errors.
 * Type, nonnegative size, and content remain unchanged for the lifetime of the open handle.
 * type returns only COMMIT, TREE, BLOB, or TAG; delta encodings have already been resolved.
 * Offsets address content bytes, excluding loose-object headers, compression, and delta instructions.
 *
 * <p>read accepts any writable ByteBuffer, including heap, direct, and sliced buffers; implementations must
 * not require an accessible backing array. It writes from the destination's position up to its limit,
 * advances position by the returned byte count, and leaves limit unchanged. Partial reads are allowed.
 * An empty destination returns zero; otherwise an offset at or beyond size returns -1. Before EOF, a read
 * into a nonempty destination returns a positive count or throws IOException, rather than returning zero.
 * Negative offsets are rejected with IllegalArgumentException, null destinations with NullPointerException,
 * and read-only destinations with ReadOnlyBufferException. There is no shared object cursor.
 *
 * <p>Reads after close fail with ClosedChannelException; repeated close calls are harmless.
 * Implementations do not retain destination buffers after read returns. This contract does not require
 * concurrent use of one handle. Storage owns any preparation, caching, or temporary files required to restore
 * content; positional access promises neither zero-copy reads nor constant-time access to compressed data.
 * The buffer contract permits future ByteBuffer-based output without exposing Netty buffers to callers.
 */
public interface ObjectRead extends AutoCloseable {
    ObjectType type();

    long size();

    int read(long offset, ByteBuffer destination) throws IOException;

    @Override
    void close() throws IOException;
}
