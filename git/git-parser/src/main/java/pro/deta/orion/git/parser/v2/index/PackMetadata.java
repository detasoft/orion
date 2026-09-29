package pro.deta.orion.git.parser.v2.index;

import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackChecksum;
import pro.deta.orion.git.parser.v2.id.PackId;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Completed pack and its backend-defined storage location. Count, size and checksum describe
 * the final self-contained Git-compatible export, not the backend's internal byte representation.
 */
public record PackMetadata(PackId packId, PackChecksum packChecksum, String storageLocation,
                           long objectCount, long packSize) {
    public PackMetadata {
        Objects.requireNonNull(packId, "packId");
        Objects.requireNonNull(packChecksum, "packChecksum");
        Objects.requireNonNull(storageLocation, "storageLocation");
        if (storageLocation.isBlank() || objectCount < 0 || objectCount > 0xffff_ffffL
                || packSize < 12 + packChecksum.byteLength()) {
            throw new IllegalArgumentException("Invalid completed pack metadata");
        }
    }

    public void validateObjects(List<IndexedObject> objects) throws IOException {
        if (objectCount != objects.size()) {
            throw new IOException("Pack object count does not match its indexed entries");
        }
        Set<ObjectId> ids = new HashSet<>();
        for (IndexedObject object : objects) {
            if (!packId.equals(object.packId())) {
                throw new IOException("Indexed object belongs to another pack");
            }
            ids.add(object.objectId());
        }
        for (IndexedObject object : objects) {
            if (object.delta().isPresent() && !ids.contains(object.delta().orElseThrow().baseId())) {
                throw new IOException("Pack is missing a delta base");
            }
        }
    }
}
