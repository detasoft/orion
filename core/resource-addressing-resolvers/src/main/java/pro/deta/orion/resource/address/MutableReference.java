package pro.deta.orion.resource.address;

import java.io.IOException;

public interface MutableReference extends ImmutableReference {
    String write(byte[] bytes, String expectedVersion) throws IOException;
}
