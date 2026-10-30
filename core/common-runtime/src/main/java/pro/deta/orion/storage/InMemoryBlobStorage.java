package pro.deta.orion.storage;

import java.io.IOException;
import java.util.function.Consumer;
import pro.deta.orion.util.Subscription;
import java.util.Arrays;
import java.util.Objects;

/**
 * Stores opaque bytes in memory with conditional publication.
 * TODO Implement change subscriptions; subscriptions currently receive no notifications.
 */
public class InMemoryBlobStorage implements BlobStorage {
    private byte[] bytes;
    private long version;

    @Override
    public Subscription subscribe(Consumer<BlobChanged> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        return () -> {};
    }

    @Override
    public synchronized Content load() throws IOException {
        return bytes == null ? new Content(null, null) : new Content(versionString(), read(versionString()));
    }

    @Override
    public synchronized byte[] read(String revision) throws IOException {
        Objects.requireNonNull(revision, "revision");
        if (bytes == null || !revision.equals(versionString())) {
            throw new BlobStorageConflictException("Blob revision is no longer available: " + revision);
        }
        return Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public synchronized String write(Modification modification) throws IOException {
        byte[] newBytes = modification.bytes();
        String expectedVersion = modification.expectedRevision();
        if (newBytes == null) {
            throw new IllegalArgumentException("Blob bytes must not be null");
        }
        String currentVersion = bytes == null ? null : versionString();
        if (!matchesExpectedVersion(currentVersion, expectedVersion)) {
            throw new BlobStorageConflictException("Blob store changed before save");
        }
        bytes = Arrays.copyOf(newBytes, newBytes.length);
        version++;
        return versionString();
    }

    private static boolean matchesExpectedVersion(String currentVersion, String expectedVersion) {
        if (currentVersion == null) {
            return expectedVersion == null;
        }
        return currentVersion.equals(expectedVersion);
    }

    private String versionString() {
        return Long.toString(version);
    }
}
