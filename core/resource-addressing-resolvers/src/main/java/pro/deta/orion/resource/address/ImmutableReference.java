package pro.deta.orion.resource.address;

import java.io.IOException;
import java.util.Optional;

public interface ImmutableReference {
    Optional<ResourceContent> read() throws IOException;

    default Optional<String> readString() throws IOException {
        return read().map(ResourceContent::asUtf8String);
    }
}
