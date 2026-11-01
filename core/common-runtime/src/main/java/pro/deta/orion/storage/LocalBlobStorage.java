package pro.deta.orion.storage;

import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.util.VcChangeDescription;
import pro.deta.orion.util.VcModificationException;
import pro.deta.orion.util.VcRevision;

import java.nio.file.Path;
import java.util.Arrays;

public class LocalBlobStorage extends BlobStorage {
    private final Path path;

    public LocalBlobStorage(Path path, OrionEventManager events) {
        super(events);
        this.path = LocalBlobStorageSupport.location(path);
    }

    @Override
    protected BlobStorageOperations createOperation(VcRevision.ReadTarget target) {
        byte[] initial = LocalBlobStorageSupport.readContent(path, target);
        VcRevision.Stored base = new VcRevision.Stored(BlobRevisions.sha256(initial));
        return new BlobStorageOperations(base) {
            private byte[] content = initial;

            @Override
            public byte[] readContent() {
                return Arrays.copyOf(content, content.length);
            }

            @Override
            public void write(byte[] bytes) {
                byte[] copy = Arrays.copyOf(bytes, bytes.length);
                if (content != initial) Arrays.fill(content, (byte) 0);
                content = copy;
            }

            @Override
            protected VcRevision.Stored commitChanges(VcChangeDescription description) {
                if (content == initial) return revision();
                VcRevision actual = LocalBlobStorageSupport.revision(path);
                if (!revision().equals(actual)) throw new VcModificationException(revision(), actual);
                VcRevision.Stored revision = new VcRevision.Stored(BlobRevisions.sha256(content));
                LocalBlobStorageSupport.write(path, content);
                publishChange(revision(), revision);
                return revision;
            }

            @Override
            public void close() {
                Arrays.fill(initial, (byte) 0);
                if (content != initial) Arrays.fill(content, (byte) 0);
            }
        };
    }
}
