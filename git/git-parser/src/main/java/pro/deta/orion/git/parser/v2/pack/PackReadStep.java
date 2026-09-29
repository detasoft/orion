package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackChecksum;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Ordered results of reading a pack. Bytes are borrowed until the next reader operation and must be
 * consumed synchronously. EntryEnd follows the entry bytes, End follows the verified trailer bytes.
 * EntryEnd validates zlib and inflated size; only full objects have a computed ID.
 * Delta semantics and base existence are not checked. End verifies the pack checksum, not resolvability.
 */
public sealed interface PackReadStep {
    record Bytes(ByteBuffer data) implements PackReadStep {}

    record EntryEnd(PackEntry metadata, Optional<ObjectId> objectId) implements PackReadStep {}

    record End(PackChecksum id) implements PackReadStep {}
}
