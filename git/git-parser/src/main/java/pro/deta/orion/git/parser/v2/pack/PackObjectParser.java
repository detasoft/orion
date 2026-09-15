package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.data.GitObjectRead;
import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.net.io.BufferedByteInput;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Stateless parsing of one physical pack entry, choosing the retained result from its type.
 * parseEntry consumes the header and complete zlib stream, validating the encoding and inflated length.
 * For COMMIT, TREE, BLOB, and TAG it streams inflated bytes into the canonical object hash and returns
 * HashedGitObjectRead with type, size, and ObjectId, without retaining inflated content.
 * For OFS_DELTA and REF_DELTA it returns ContentGitObjectRead exposing delta instructions for the current
 * reconstruction. That result exposes the delta type and instruction size, not the final object's type
 * or size. The parser never fetches bases, applies deltas, or treats a delta instruction hash as an ObjectId.
 * No mode parameter is needed: both paths consume content, but retain different results.
 *
 * <p>Original header, base-reference, and compressed bytes are forwarded to the borrowed rawSink.
 * Parsing uses bounded working buffers. Content reads may rely on retained original pack bytes rather than
 * an object-sized array. No separate inflated-content store or cache for later consumers is required.
 * offset is the absolute entry-header position; dataOffset is the absolute start of its zlib stream.
 * Compressed entry length is not stored in the header, so parsing traverses the stream to locate its end.
 * Prefetched bytes after that boundary stay available through the same buffered input and are not forwarded.
 * Sink writes complete before buffers are reused. Failures and truncated or malformed data are IOException;
 * no successful result is returned, and parser-owned temporary content is released. Partial raw writes may
 * already have happened; the caller owns rollback. Neither source nor rawSink is closed.
 *
 * <p>The caller owns the returned object's read resources. PackUpload retains original pack bytes and offsets
 * for deferred resolution; later consumers reread them instead of retaining this result's inflated content.
 * Result separates transient read ownership from Entry metadata stored in PackIndex: the index does not
 * retain live read handles. Pack header, count, checksum, and index state belong to the caller.
 * Method body remains a placeholder; no parser or content-storage implementation is provided here.
 */
public final class PackObjectParser {
    private PackObjectParser() {
    }

    public static Result parseEntry(BufferedByteInput source, long offset, WritableByteChannel rawSink)
            throws IOException {
        throw new UnsupportedOperationException("Pack entry parsing is not implemented");
    }

    /**
     * Physical metadata without live content handles or a resolved ObjectId. inflatedSize counts full content
     * bytes or delta instructions. OFS_DELTA has only baseOffset, decoded to an absolute earlier entry offset;
     * REF_DELTA has only baseId, which may refer inside or outside the pack. Full entries have neither field.
     * Parsing validates these combinations and boundaries before returning metadata.
     */
    public record Entry(long offset, long dataOffset, long inflatedSize, ObjectType type,
                        OptionalLong baseOffset, Optional<ObjectId> baseId) {
    }

    /**
     * Parsed metadata and the result of consuming its payload. object.type and object.size match entry.type
     * and entry.inflatedSize. Full entries carry HashedGitObjectRead; deltas carry ContentGitObjectRead.
     * The caller closes object after use. Closing a result exposed by PackUpload.next releases its read handle,
     * while the upload retains backing content and index records for subsequent resolution or commit.
     */
    public record Result(Entry entry, GitObjectRead object) {
    }
}
