package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.id.PackChecksum;

import java.nio.ByteBuffer;

/**
 * Pull results for a Git pack. Entry starts with input-stream metadata; Bytes contains only compressed
 * content, borrowed until the next operation. EntryEnd follows the exact zlib boundary. End verifies
 * the input pack checksum. Object hashing and delta resolution belong to the ingestor.
 */
public sealed interface PackReadStep {
    record Entry(PackEntry metadata) implements PackReadStep {}

    record Bytes(ByteBuffer data) implements PackReadStep {}

    record EntryEnd(PackEntry metadata, long compressedSize) implements PackReadStep {}

    record End(PackChecksum checksum) implements PackReadStep {}
}
