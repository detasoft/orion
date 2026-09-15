package pro.deta.orion.git.parser.v2.pack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

/**
 * Combined append sink and positional byte source owned by one PackUpload.
 * The parser borrows this complete interface, and its content results read from the same retained bytes.
 * write appends at the end, advances the source buffer by the accepted count, preserves limit, and does not
 * retain the buffer. Partial writes are allowed;
 * a nonempty write makes progress or throws IOException. Earlier bytes are never overwritten or discarded.
 * read provides positional access to all accepted bytes, including any buffered writes, without changing
 * the append position. Long offsets are relative to the pack header. Reads accept any writable ByteBuffer,
 * advance its position, preserve its limit, and do not retain it. Partial reads are allowed; an empty buffer
 * returns zero, otherwise a read returns a positive count or -1 at or beyond the currently retained prefix.
 * That prefix grows as writes append bytes. Negative offsets fail with IllegalArgumentException, null buffers
 * with NullPointerException, and read-only buffers with ReadOnlyBufferException. Reads see successful writes
 * immediately, including buffered bytes, without requiring force or disturbing the append position.
 * Content reads decompress these original bytes; they do not write them back or update the pack checksum.
 * All operations are sequential within one upload; no concurrent-use guarantee is required.
 *
 * <p>The initial implementation will use a private FileChannel opened for reading and writing. Sequential
 * writes advance its position; reads use FileChannel.read(destination, offset). FileChannel and file paths
 * never cross this interface. No memory tier, mapping, or separate cache is required for that implementation.
 * force makes accepted bytes durable in the backing file before publication. Storage remains responsible
 * for persisting the index, manifest, and directory changes needed for the complete commit guarantee.
 * close is idempotent and releases the backing channel; it does not delete or publish the pack. Reads and
 * writes after close fail with ClosedChannelException. The upload owns close and staging-file cleanup;
 * the parser and content handles borrow this store and never close it. Handles are closed before the owning
 * upload releases the store. This is a contract; no accumulator implementation exists yet.
 */
public interface PackByteStore extends WritableByteChannel {
    @Override
    int write(ByteBuffer source) throws IOException;

    int read(long offset, ByteBuffer destination) throws IOException;

    void force() throws IOException;
}
