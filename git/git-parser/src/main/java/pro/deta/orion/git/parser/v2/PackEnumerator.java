package pro.deta.orion.git.parser.v2;

import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.net.io.BufferedByteInput;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Sequentially parses one pack from caller-owned input, independently of repository storage and resolution.
 * next returns the next physical entry, or null only after the declared entries and checksum are verified.
 * read streams the current entry's inflated payload into a writable ByteBuffer: full object content or delta
 * instructions, never an automatically resolved delta. Partial reads advance position and preserve limit;
 * an empty destination returns zero and payload EOF returns -1. Buffers are not retained.
 * next drains and validates any unread payload before advancing; entry metadata remains valid afterward,
 * but the payload cursor belongs only to the current entry. Premature EOF and malformed packs are IOException.
 * Parsing stops after the checksum, leaving subsequent protocol bytes available through the same input.
 * Closing releases parser resources without closing the source. No concurrent-use guarantee is required.
 *
 * <p>Preliminary methods: open(source) creates a parser, next() advances, read(destination) reads payload,
 * and close() releases the parser. Storage can wrap this interface to track entries and retain consumed bytes.
 * The ingestor owns its ordinary next/read loop, hashing and delta resolution. No callbacks or storage calls
 * occur in the independent parser. The factory remains a placeholder; actual parsing is not implemented.
 */
public interface PackEnumerator extends AutoCloseable {
    static PackEnumerator open(BufferedByteInput source) throws IOException {
        throw new UnsupportedOperationException("Pack enumeration is not implemented");
    }

    Entry next() throws IOException;

    int read(ByteBuffer destination) throws IOException;

    @Override
    void close() throws IOException;

    /**
     * Physical entry metadata available before reading its payload. Offsets address the original pack bytes;
     * dataOffset starts the zlib stream and inflatedSize counts content bytes or delta instructions.
     * OFS_DELTA has only baseOffset, decoded to an absolute earlier entry offset. REF_DELTA has only baseId,
     * which may refer inside or outside the pack. Full entries have neither base field.
     * The parser validates these combinations and boundaries; this value does not contain a resolved ObjectId.
     */
    record Entry(long offset, long dataOffset, long inflatedSize, Type type,
                 OptionalLong baseOffset, Optional<ObjectId> baseId) {
    }

    /**
     * Encoding found in a pack header; delta values describe storage representation, not logical object type.
     */
    enum Type {
        COMMIT, TREE, BLOB, TAG, OFS_DELTA, REF_DELTA
    }
}
