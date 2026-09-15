package pro.deta.orion.git.parser.v2.data;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Caller-owned content result, separate from the GitObjectRead processing function.
 * type and nonnegative size describe stable inflated payload. For a physical delta this means instructions;
 * GitPackObjectResolver.getObject returns restored COMMIT, TREE, BLOB, or TAG content instead.
 * Implementations own their backing read resources independently of any borrowed processing input.
 * No separate persistent content copy is required; original pack bytes and index offsets remain authoritative.
 *
 * <p>read uses inflated-content-relative offsets and accepts writable heap, direct, and sliced ByteBuffers.
 * It advances position, preserves limit, retains no destination, and allows partial reads. Empty destinations
 * return zero; otherwise reads return a positive count or -1 at or beyond size. Negative offsets fail with
 * IllegalArgumentException, null buffers with NullPointerException, and read-only buffers with
 * ReadOnlyBufferException. Truncated backing data is IOException. Positional access may repeat decompression.
 *
 * <p>close is idempotent, releases this result's resources, and never closes the owning upload or repository.
 * Reads afterward fail with ClosedChannelException. Callers close results before their provider.
 * Keep restored bytes only for current reconstruction or open reads; later consumers reread and reconstruct
 * the original pack. Future-consumer tracking and an LRU are deferred. No concurrent-use guarantee is required.
 */
public interface GitObjectContent extends AutoCloseable {
    ObjectType type();

    long size();

    int read(long offset, ByteBuffer destination) throws IOException;

    @Override
    void close() throws IOException;
}
