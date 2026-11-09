package pro.deta.orion.storage;

import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.util.VcChangeDescription;
import pro.deta.orion.util.VcModificationException;
import pro.deta.orion.util.VcRevision;

import java.util.Arrays;
import java.io.FileNotFoundException;
import java.io.UncheckedIOException;
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

    private VcRevision currentRevision() {
        return versions.isEmpty() ? new VcRevision.Absent() : versions.lastEntry().getKey();
    }

    @Override
    protected BlobStorageOperations createOperation(VcRevision.ReadTarget target) {
        VcRevision base = switch (target) {
            case VcRevision.Latest ignored -> currentRevision();
            case VcRevision.Stored stored -> stored;
            case VcRevision.Absent absent -> absent;
        };
        if (base instanceof VcRevision.Absent absent) {
            VcRevision current = currentRevision();
            if (!absent.equals(current)) throw new VcModificationException(absent, current);
        }
        byte[] initial = versions.get(base);
        if (initial == null && base instanceof VcRevision.Stored stored) {
            throw new IllegalArgumentException("Blob revision is not available: " + stored.value());
        }
        return new BlobStorageOperations(base) {
            private byte[] content = initial;

            @Override
            public byte[] readContent() {
                if (content == null) throw new UncheckedIOException(new FileNotFoundException("Blob is absent"));
                return Arrays.copyOf(content, content.length);
            }

            @Override
            public void write(byte[] bytes) {
                if (readOnly) throw new IllegalStateException("Blob store is read-only");
                content = Arrays.copyOf(bytes, bytes.length);
            }

            @Override
            protected VcRevision.Stored commitChanges(VcChangeDescription description) {
                if (content == initial && revision() instanceof VcRevision.Stored stored) return stored;
                if (content == null) throw new IllegalStateException("No blob content prepared");
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
