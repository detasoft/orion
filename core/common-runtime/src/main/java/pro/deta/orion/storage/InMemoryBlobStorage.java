package pro.deta.orion.storage;

import pro.deta.orion.util.VcRevision;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class InMemoryBlobStorage extends BlobStorage {
    private final boolean readOnly;
    private final LinkedHashMap<VcRevision.Stored, byte[]> versions = new LinkedHashMap<>();

    public InMemoryBlobStorage() {
        readOnly = false;
    }

    public InMemoryBlobStorage(byte[] content, boolean readOnly) {
        Objects.requireNonNull(content, "content");
        this.readOnly = readOnly;
        versions.put(new VcRevision.Stored("1"), Arrays.copyOf(content, content.length));
    }

    @Override
    protected VcRevision readRevision() {
        return versions.isEmpty() ? new VcRevision.Absent() : versions.lastEntry().getKey();
    }

    @Override
    protected byte[] readContent(VcRevision.ReadTarget revision) {
        byte[] content = switch (revision) {
            case VcRevision.Latest ignored -> {
                Map.Entry<VcRevision.Stored, byte[]> latest = versions.lastEntry();
                if (latest == null) {
                    throw new IllegalStateException("Blob is absent");
                }
                yield latest.getValue();
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
        VcRevision.Stored revision = new VcRevision.Stored(Long.toString((long) versions.size() + 1));
        versions.put(revision, Arrays.copyOf(content, content.length));
    }
}
