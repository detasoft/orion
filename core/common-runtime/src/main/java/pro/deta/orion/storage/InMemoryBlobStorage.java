package pro.deta.orion.storage;

import pro.deta.orion.util.VcChangeDescription;
import pro.deta.orion.util.VcRevision;

import java.util.Arrays;
import java.util.Objects;

/**
 * Stores the current blob in memory with a fixed read-only policy.
 * Synchronization, revision checks, and change notifications belong to the shared Vc model.
 */
public class InMemoryBlobStorage extends BlobStorage {
    private final boolean readOnly;
    private byte[] bytes;
    private long version;

    public InMemoryBlobStorage() {
        readOnly = false;
    }

    public InMemoryBlobStorage(byte[] content, boolean readOnly) {
        Objects.requireNonNull(content, "content");
        this.bytes = Arrays.copyOf(content, content.length);
        this.version = 1;
        this.readOnly = readOnly;
    }

    @Override
    protected VcRevision readRevision() {
        return bytes == null ? new VcRevision.Absent() : new VcRevision.Stored(versionString());
    }

    @Override
    protected byte[] readContent() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    protected void write(byte[] content) {
        if (readOnly) {
            throw new IllegalStateException("Blob store is read-only");
        }
        bytes = Arrays.copyOf(content, content.length);
    }

    @Override
    protected VcRevision.Stored commit(VcChangeDescription description) {
        version++;
        return new VcRevision.Stored(versionString());
    }

    private String versionString() {
        return Long.toString(version);
    }
}
