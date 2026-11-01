package pro.deta.orion.storage;

import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.util.VcChangeDescription;
import pro.deta.orion.util.VcModificationException;
import pro.deta.orion.util.VcRevision;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Objects;

public class InMemoryBlobStorage extends BlobStorage {
    private final boolean readOnly;
    private final LinkedHashMap<VcRevision.Stored, byte[]> versions = new LinkedHashMap<>();

    public InMemoryBlobStorage(OrionEventManager events) {
        super(events);
        readOnly = false;
    }

    public InMemoryBlobStorage(byte[] content, boolean readOnly, OrionEventManager events) {
        super(events);
        Objects.requireNonNull(content, "content");
        this.readOnly = readOnly;
        versions.put(new VcRevision.Stored("1"), Arrays.copyOf(content, content.length));
    }

    private VcRevision.Stored currentRevision() {
        if (versions.isEmpty()) throw new IllegalStateException("Blob is absent");
        return versions.lastEntry().getKey();
    }

    @Override
    protected BlobStorageOperations createOperation(VcRevision.ReadTarget target) {
        if (!(target instanceof VcRevision.Stored) && !(target instanceof VcRevision.Latest)) {
            throw new IllegalArgumentException("Blob reads require a stored or latest revision");
        }
        VcRevision.Stored base = target instanceof VcRevision.Stored stored ? stored : currentRevision();
        byte[] initial = versions.get(base);
        if (initial == null) {
            throw new IllegalArgumentException("Blob revision is not available: " + base.value());
        }
        return new BlobStorageOperations(base) {
            private byte[] content = initial;

            @Override
            public byte[] readContent() {
                return Arrays.copyOf(content, content.length);
            }

            @Override
            public void write(byte[] bytes) {
                if (readOnly) throw new IllegalStateException("Blob store is read-only");
                content = Arrays.copyOf(bytes, bytes.length);
            }

            @Override
            protected VcRevision.Stored commitChanges(VcChangeDescription description) {
                if (content == initial) return revision();
                VcRevision actual = currentRevision();
                if (!revision().equals(actual)) throw new VcModificationException(revision(), actual);
                VcRevision.Stored revision = new VcRevision.Stored(Long.toString((long) versions.size() + 1));
                versions.put(revision, content);
                publishChange(revision(), revision);
                return revision;
            }

            @Override
            public void close() {}
        };
    }
}
