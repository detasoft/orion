package pro.deta.orion.git.parser.v2.data;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Provides positional access to the original bytes of a pack opened through GitStorageApi.
 * Offsets and size cover the complete pack, including its header, encoded entries, and checksum trailer.
 * The caller closes the handle; storage keeps its bytes available and unchanged until close, even if another
 * operation publishes the pack or requests quarantine cleanup. The handle can represent a quarantined or
 * published pack; opening it does not resolve objects or change publication state.
 *
 * <p>read follows ObjectRead's buffer and EOF contract: accept any writable heap, direct, or sliced ByteBuffer
 * without requiring a backing array, advance position by the bytes read, and preserve limit. Partial reads
 * are allowed. An empty destination returns zero; otherwise an offset at or beyond size returns -1.
 * Before EOF, a read into a nonempty destination returns a positive count or throws IOException.
 * Negative offsets fail with IllegalArgumentException, null destinations with NullPointerException,
 * and read-only destinations with ReadOnlyBufferException. No shared cursor or concurrent-use guarantee exists.
 *
 * <p>Storage does not retain destination buffers after read returns. Reads after close fail with
 * ClosedChannelException; repeated close calls are harmless. Closing releases this handle's resources and
 * retention of pack bytes without deleting data still needed by publication or other open handles.
 */
public interface PackRead extends AutoCloseable {
    long size();

    int read(long offset, ByteBuffer destination) throws IOException;

    @Override
    void close() throws IOException;
}
