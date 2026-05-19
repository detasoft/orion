package pro.deta.orion.resource.reference.resolver;

import java.util.Optional;

public interface S3ObjectContentReader {
    Optional<byte[]> read(S3ObjectLocation location);
}
