package pro.deta.orion.storage;

import java.io.IOException;
import java.util.function.Consumer;
import pro.deta.orion.util.Subscription;
import java.util.Arrays;
import java.util.Objects;

public class ReadOnlyBlobStorage implements BlobStorage {
    private final byte[] bytes;
    private final String sourceName;

    public ReadOnlyBlobStorage(byte[] bytes, String sourceName) {
        if (bytes == null) {
            throw new IllegalArgumentException("Blob bytes must not be null");
        }
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException("Blob source name must not be empty");
        }
        this.bytes = Arrays.copyOf(bytes, bytes.length);
        this.sourceName = sourceName;
    }

    @Override
    public Subscription subscribe(Consumer<BlobChanged> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        return () -> {};
    }

    @Override
    public Content load() {
        return new Content(BlobRevisions.sha256(bytes), Arrays.copyOf(bytes, bytes.length));
    }

    @Override
    public byte[] read(String revision) throws IOException {
        Objects.requireNonNull(revision, "revision");
        if (!revision.equals(BlobRevisions.sha256(bytes))) {
            throw new BlobStorageConflictException("Blob revision is no longer available: " + revision);
        }
        return Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public String write(Modification modification) throws IOException {
        throw new IOException("Blob store is read-only: " + sourceName);
    }
}
