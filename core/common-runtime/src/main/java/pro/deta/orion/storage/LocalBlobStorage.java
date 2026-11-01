package pro.deta.orion.storage;

import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.util.VcChangeDescription;
import pro.deta.orion.util.VcRevision;

import java.nio.file.Path;
import java.util.Arrays;

public class LocalBlobStorage extends BlobStorage {
    private final Path path;

    public LocalBlobStorage(Path path, OrionEventManager events) {
        this(events, LocalBlobStorageSupport.location(path));
    }

    private LocalBlobStorage(OrionEventManager events, Path path) {
        super(events, LocalBlobStorageSupport.revision(path));
        this.path = path;
    }

    @Override
    protected BlobStorageReadOperations createReadOperation(VcRevision.ReadTarget revision) {
        byte[] content = LocalBlobStorageSupport.readContent(path, revision);
        return () -> Arrays.copyOf(content, content.length);
    }

    @Override
    protected BlobStorageUpdateOperations createUpdateOperation() {
        return new BlobStorageUpdateOperations() {
            @Override
            public void write(byte[] content) {
                LocalBlobStorageSupport.write(path, content);
            }

            @Override
            protected VcRevision commit(VcChangeDescription description) {
                return LocalBlobStorageSupport.revision(path);
            }
        };
    }
}
