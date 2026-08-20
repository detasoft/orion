package pro.deta.orion.git.nativestorage.pack;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public record PublishedPackContent(
        PublishedPackManifest manifest,
        InputStream input) implements AutoCloseable {
    public PublishedPackContent {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(input, "input");
    }

    @Override
    public void close() throws IOException {
        input.close();
    }
}
