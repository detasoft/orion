package pro.deta.orion.git.parser.v2.index;

import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;

import java.util.Objects;
import java.util.Optional;

/**
 * Verified object and the location of its compressed bytes relative to the stored pack.
 * Object size and type describe the resolved object; DELTA instruction size describes its inflated delta data.
 */
public record IndexedObject(PackId packId, ObjectId objectId, GitObjectType type, long objectSize,
                            long packOffset, long compressedSize, Optional<Delta> delta) {
    public IndexedObject {
        Objects.requireNonNull(packId, "packId");
        Objects.requireNonNull(objectId, "objectId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(delta, "delta");
        if (type == GitObjectType.OFS_DELTA || type == GitObjectType.REF_DELTA || objectSize < 0
                || packOffset < 0 || compressedSize <= 0 || compressedSize > Long.MAX_VALUE - packOffset) {
            throw new IllegalArgumentException("Invalid verified object metadata");
        }
    }

    public record Delta(ObjectId baseId, long instructionSize) {
        public Delta {
            Objects.requireNonNull(baseId, "baseId");
            if (instructionSize < 0) {
                throw new IllegalArgumentException("Negative delta instruction size");
            }
        }
    }
}
