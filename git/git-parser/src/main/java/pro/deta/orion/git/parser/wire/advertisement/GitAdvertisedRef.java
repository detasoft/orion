package pro.deta.orion.git.parser.wire.advertisement;

import java.util.Objects;
import java.util.Optional;

public record GitAdvertisedRef(
        String objectId,
        String name,
        Optional<String> peeledObjectId) {

    public GitAdvertisedRef {
        Objects.requireNonNull(objectId, "objectId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(peeledObjectId, "peeledObjectId");
    }

    public static GitAdvertisedRef direct(String objectId, String name) {
        return new GitAdvertisedRef(objectId, name, Optional.empty());
    }

    public GitAdvertisedRef withPeeledObjectId(String value) {
        return new GitAdvertisedRef(objectId, name, Optional.of(Objects.requireNonNull(value, "value")));
    }
}
