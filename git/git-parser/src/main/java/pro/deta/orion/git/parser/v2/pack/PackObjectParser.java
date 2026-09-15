package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.data.GitObjectRead;
import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.net.io.BufferedByteInput;

import java.io.IOException;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Stateless parsing of one physical pack entry with a caller-selected payload processor.
 * parseEntry consumes the header and complete zlib stream, validating the encoding and inflated length.
 * It calls reader with physical type, declared inflated size, and a bounded borrowed zlib source; Result
 * contains the returned value alongside Entry metadata. No reader or returned value may retain that source.
 * HashedGitObjectRead computes IDs for full objects; ContentGitObjectRead processes inflated content or
 * delta instructions. The parser never fetches bases, applies deltas, or hashes instructions as an ObjectId.
 *
 * <p>Original header, base-reference, and compressed bytes are appended to the borrowed PackByteStore once.
 * The caller has already retained the pack prefix up to offset. Parsing uses bounded working buffers;
 * compressed length is absent from the header, so the stream must be traversed to locate its end.
 * The provider drains and validates payload not consumed by reader before reporting success. Prefetched
 * bytes after the boundary remain available through the same buffered input and are not appended.
 * Sink writes complete before buffers are reused. Neither source nor byteStore is closed.
 * Failures and malformed or truncated data are IOException; partial raw writes require caller rollback.
 * A resource-bearing result requires cleanup if validation fails before it can be returned to its caller.
 *
 * <p>PackUpload uses Optional<ObjectId> as the value: a full object's hash or empty for a delta whose original
 * bytes remain available for resolver reads. The index retains metadata, never processors or live read handles.
 * Other callers can select their own result and own any resources it contains. Pack header, count, checksum,
 * and index state belong to the caller. The method body remains a placeholder.
 */
public final class PackObjectParser {
    private PackObjectParser() {
    }

    public static <R> Result<R> parseEntry(BufferedByteInput source, long offset, PackByteStore byteStore,
                                            GitObjectRead<R> reader)
            throws IOException {
        throw new UnsupportedOperationException("Pack entry parsing is not implemented");
    }

    /**
     * Physical metadata without live content handles or a resolved ObjectId. offset locates the entry header;
     * dataOffset locates its zlib stream, both absolute pack offsets. inflatedSize counts full content
     * bytes or delta instructions. OFS_DELTA has only baseOffset, decoded to an absolute earlier entry offset;
     * REF_DELTA has only baseId, which may refer inside or outside the pack. Full entries have neither field.
     * Parsing validates these combinations and boundaries before returning metadata.
     */
    public record Entry(long offset, long dataOffset, long inflatedSize, ObjectType type,
                        OptionalLong baseOffset, Optional<ObjectId> baseId) {
    }

    /**
     * Physical metadata and the nonnull value returned by the payload processor. Resource ownership belongs
     * to the caller only after successful parsing. Entry can be indexed independently of the transient value.
     */
    public record Result<R>(Entry entry, R value) {
    }
}
