package pro.deta.orion.git.parser.v2.storage;

import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;

import java.util.Objects;
import java.util.Optional;

/**
 * Describes a conditional ref update submitted through GitStorageApi.
 * An empty expectedOld requires the ref to be absent (creation); an empty newId requests deletion.
 * With both IDs present, replace the value only if it matches expectedOld, including for force updates.
 * The ref and both Optional containers must be non-null; both IDs cannot be absent at once.
 * Storage checks the expected value under the ref lock. A failed ref update does not undo pack publication.
 */
public record RefUpdate(RefId ref, Optional<ObjectId> expectedOld, Optional<ObjectId> newId) {
    public RefUpdate {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(expectedOld, "expectedOld");
        Objects.requireNonNull(newId, "newId");
        if (expectedOld.isEmpty() && newId.isEmpty()) {
            throw new IllegalArgumentException("Ref update must contain an expected or new object ID");
        }
    }
}
