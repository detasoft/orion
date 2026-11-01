package pro.deta.orion.storage;

import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.util.VcResource;
import pro.deta.orion.util.VcRevision;

public abstract class BlobStorage extends VcResource<BlobStorage.BlobStorageOperations> {
    protected BlobStorage(OrionEventManager eventManager) {
        super(eventManager);
    }

    public final byte[] readBytes(VcRevision revision) {
        try (BlobStorageOperations operation = open(revision)) {
            return operation.readContent();
        }
    }

    public abstract class BlobStorageOperations extends VcOperation {
        protected BlobStorageOperations(VcRevision.Stored revision) {
            super(revision);
        }

        public abstract byte[] readContent();

        public abstract void write(byte[] bytes);
    }
}
