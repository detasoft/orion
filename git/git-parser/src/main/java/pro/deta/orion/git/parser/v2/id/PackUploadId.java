package pro.deta.orion.git.parser.v2.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifies one pack upload before its content checksum is known, independently of GitId.
 * Assigned before reception and used to locate that upload's prepared data inside repository storage.
 * Concurrent uploads of identical content have distinct upload IDs but resolve to the same verified PackId.
 * The identifier exposes neither a file path nor the storage representation of the upload.
 */
public record PackUploadId(UUID value) {
    public PackUploadId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
