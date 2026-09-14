package pro.deta.orion.git.parser.v2.data;

import pro.deta.orion.git.parser.v2.id.ObjectId;

import java.util.Objects;

/**
 * Describes one physical pack entry before object resolution.
 * offset addresses the entry header; dataOffset addresses the zlib stream after the header and base reference.
 * inflatedSize is the uncompressed payload size: object content for Full, delta instructions for delta entries.
 * crc32 holds the unsigned CRC32 bit pattern of the entire packed entry, including its header and base reference.
 * The scanner validates entry boundaries and base offsets. Resolved object IDs and external-base classification
 * belong to the later resolution step.
 */
public record PackEntry(long offset, long dataOffset, long inflatedSize, Representation representation, int crc32) {
    public PackEntry {
        Objects.requireNonNull(representation, "representation");
    }

    /**
     * Describes the entry's physical encoding independently of the resolved object's logical type.
     */
    public sealed interface Representation permits Full, OfsDelta, RefDelta {
    }

    /**
     * Stores complete object content with its known logical type.
     */
    public record Full(ObjectType type) implements Representation {
        public Full {
            Objects.requireNonNull(type, "type");
        }
    }

    /**
     * References an earlier entry by its absolute header offset in this pack, decoded and checked by the scanner.
     */
    public record OfsDelta(long baseOffset) implements Representation {
    }

    /**
     * References a base object by ID; resolution determines whether the base is inside this pack or external.
     */
    public record RefDelta(ObjectId baseId) implements Representation {
        public RefDelta {
            Objects.requireNonNull(baseId, "baseId");
        }
    }
}
