package pro.deta.orion.git.parser.v2.pack;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Exposes exactly one original zlib stream, validating its inflated size with bounded scratch space.
 * Input has no peek or unread operation, so the inflater receives one compressed byte at a time to avoid
 * consuming the following entry. Bulk reads propagate validation failures instead of hiding them after
 * a partial read. Closing releases the inflater only; the input remains borrowed.
 *
 * @deprecated Replace with a V2 source when migrating IndexedPack's stored readers, preserving validation
 * of partially written entries and unread payloads.
 */
@Deprecated(forRemoval = true)
final class ZlibBoundaryInputStream extends InputStream {
    private final InputStream input;
    private final long expectedSize;
    private final Inflater inflater = new Inflater();
    private final byte[] compressed = new byte[1];
    private final byte[] inflated = new byte[8192];
    private long inflatedSize;

    ZlibBoundaryInputStream(InputStream input, long expectedSize) {
        this.input = input;
        this.expectedSize = expectedSize;
    }

    @Override
    public int read() throws IOException {
        if (inflater.finished()) {
            return -1;
        }
        int value = input.read();
        if (value < 0) {
            throw new EOFException("Truncated zlib stream");
        }
        compressed[0] = (byte) value;
        inflater.setInput(compressed);
        do {
            int count;
            try {
                count = inflater.inflate(inflated);
            } catch (DataFormatException error) {
                throw new IOException("Invalid pack zlib stream", error);
            }
            if (count > expectedSize - inflatedSize) {
                throw new IOException("Inflated size exceeds declared object size");
            }
            inflatedSize += count;
            if (inflater.finished()) {
                if (inflatedSize != expectedSize) {
                    throw new IOException("Inflated size differs from declared object size");
                }
                break;
            }
            if (inflater.needsDictionary()) {
                throw new IOException("Pack zlib stream requires a dictionary");
            }
            if (count == 0 && !inflater.needsInput()) {
                throw new IOException("Pack zlib stream made no progress");
            }
        } while (!inflater.needsInput());
        return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        int count = 0;
        while (count < length) {
            int value = read();
            if (value == -1) {
                break;
            }
            bytes[offset + count++] = (byte) value;
        }
        return count == 0 && length != 0 ? -1 : count;
    }

    @Override
    public void close() {
        inflater.end();
    }
}
