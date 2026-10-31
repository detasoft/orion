package pro.deta.orion.storage;

import pro.deta.orion.util.VcChangeDescription;
import pro.deta.orion.util.VcRevision;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Stores the current blob in memory with a fixed read-only policy.
 * Synchronization, revision checks, and change notifications belong to the shared Vc model.
 */
public class InMemoryBlobStorage extends BlobStorage {
    private final boolean readOnly;
    private final Map<VcRevision.Stored, byte[]> versions = new HashMap<>();
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
        versions.put(new VcRevision.Stored(versionString()), bytes);
    }

    @Override
    protected VcRevision readRevision() {
        return bytes == null ? new VcRevision.Absent() : new VcRevision.Stored(versionString());
    }

    @Override
    protected byte[] readContent(VcRevision.ReadTarget revision) {
        byte[] content = switch (revision) {
            case VcRevision.Latest ignored -> {
                if (bytes == null) {
                    throw new IllegalStateException("Blob is absent");
                }
                yield bytes;
            }
            case VcRevision.Stored stored -> {
                byte[] selected = versions.get(stored);
                if (selected == null) {
                    throw new IllegalArgumentException("Blob revision is not available: " + stored.value());
                }
                yield selected;
            }
        };
        return Arrays.copyOf(content, content.length);
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
        VcRevision.Stored revision = new VcRevision.Stored(versionString());
        versions.put(revision, bytes);
        return revision;
    }

    private String versionString() {
        return Long.toString(version);
    }
}
