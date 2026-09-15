package pro.deta.orion.git.parser.v2.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

/**
 * Internal raw-byte accumulator owned by one pack upload, hidden behind PackUpload.
 * The iterator receives only its WritableByteChannel view. write appends at the end, advances the source
 * buffer by the accepted count, preserves limit, and does not retain the buffer. Partial writes are allowed;
 * a nonempty write makes progress or throws IOException. Earlier bytes are never overwritten or discarded.
 * read provides positional access to all accepted bytes, including any buffered writes, without changing
 * the append position. It follows PackUpload.read's ByteBuffer and current-end contract using long offsets.
 * All operations are sequential within one upload; no concurrent-use guarantee is required.
 *
 * <p>The initial implementation will use a private FileChannel opened for reading and writing. Sequential
 * writes advance its position; reads use FileChannel.read(destination, offset). FileChannel and file paths
 * never cross this interface. No memory tier, mapping, or separate cache is required for that implementation.
 * force makes accepted bytes durable in the backing file before publication. Storage remains responsible
 * for persisting the index, manifest, and directory changes needed for the complete commit guarantee.
 * close is idempotent and releases the backing channel; it does not delete or publish the pack. Reads and
 * writes after close fail with ClosedChannelException. The upload owns close and staging-file cleanup;
 * the iterator only borrows the writable view. This is a contract; no accumulator implementation exists yet.
 */
interface PackByteStore extends WritableByteChannel {
    int read(long offset, ByteBuffer destination) throws IOException;

    void force() throws IOException;
}
