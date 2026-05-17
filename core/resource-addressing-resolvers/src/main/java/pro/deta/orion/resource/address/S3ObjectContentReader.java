package pro.deta.orion.resource.address;

import java.util.Optional;

public interface S3ObjectContentReader {
    Optional<byte[]> read(S3ObjectLocation location);
}
