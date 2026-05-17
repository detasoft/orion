package pro.deta.orion.resource.address;

import java.nio.file.Path;

public record ExternalDirectory(Path path) {
    public ExternalDirectory {
        if (path == null) {
            throw new IllegalArgumentException("External directory path must not be null");
        }
    }
}
