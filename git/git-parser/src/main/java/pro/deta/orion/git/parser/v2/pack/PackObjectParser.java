package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.net.io.BufferedByteInput;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Stateless parsing of one physical pack entry from any BufferedByteInput.
 * parseEntry starts at the entry header and returns metadata after consuming that entry's zlib stream.
 * It decodes type, inflated size, and delta base reference, validates the compressed stream and inflated
 * length, and forwards original header, base-reference, and compressed bytes to the borrowed rawSink.
 * offset is the absolute entry-header position in the pack; dataOffset is the absolute zlib-stream start.
 * The parser uses bounded working buffers and does not materialize the full compressed or inflated payload.
 * Inflated bytes are discarded after validation; original bytes remain available in the caller's sink.
 * Delta instructions are not applied and object IDs are not computed here.
 *
 * <p>Compressed entry length is not stored in the header, so parsing must traverse the zlib stream to locate
 * its end. Any prefetched bytes after that end remain available through the same buffered source and are not
 * forwarded as part of this entry. Sink writes are completed before reusing buffers; failures and malformed
 * or truncated entries propagate as IOException and never return successful metadata. Partial sink writes
 * may already have happened on failure; the upload owns rollback. Neither source nor sink is closed.
 * Pack header, entry count, running offset, checksum, and index state belong to the caller. PackUpload calls
 * this method for each entry and registers the result before exposing it to the ingestor. Method body is a
 * placeholder; this class defines no storage backend or whole-object buffering requirement.
 */
public final class PackObjectParser {
    private PackObjectParser() {
    }

    public static Entry parseEntry(BufferedByteInput source, long offset, WritableByteChannel rawSink)
            throws IOException {
        throw new UnsupportedOperationException("Pack entry parsing is not implemented");
    }

    /**
     * Metadata of a physical pack entry, without retaining its payload or a resolved ObjectId.
     * inflatedSize counts object content bytes or delta instruction bytes, according to type.
     * OFS_DELTA has only baseOffset, decoded to an absolute earlier entry offset. REF_DELTA has only baseId,
     * which may refer inside or outside the pack. Full entries have neither base field.
     * Parsing validates these combinations and boundaries before returning the metadata.
     */
    public record Entry(long offset, long dataOffset, long inflatedSize, ObjectType type,
                        OptionalLong baseOffset, Optional<ObjectId> baseId) {
    }
}
