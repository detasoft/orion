package pro.deta.orion.git.parser.v2.data;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Provides positional access to inflated Git object data without requiring whole-object memory buffering.
 * PackUpload.readObject(entryOffset) exposes a physical entry's payload: full object content or delta
 * instructions, with its packed type and inflated size. Base references remain in the entry's index metadata.
 * GitStorageApi.readObject and GitPackObjectResolver.getObject expose restored content with only
 * COMMIT, TREE, BLOB, or TAG types; delta encodings have already been resolved on those paths.
 * The caller closes this handle to release resources, including after partial reads or errors.
 * Type, nonnegative size, and content remain unchanged for the lifetime of the open handle.
 * Read offsets address inflated payload bytes, excluding pack or loose-object headers and compression.
 * These payload offsets differ from the absolute pack entry offset used to open an upload read.
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
 * concurrent use of one handle. The provider owns preparation and backing resources; close releases only
 * this read, not its upload or repository. Close upload reads before rolling back their owning upload.
 * Positional access promises neither zero-copy reads nor constant-time access to compressed data.
 * The buffer contract permits future ByteBuffer-based output without exposing Netty buffers to callers.
 */
public interface GitObjectRead extends AutoCloseable {
    ObjectType type();

    long size();

    int read(long offset, ByteBuffer destination) throws IOException;

    @Override
    void close() throws IOException;
}
