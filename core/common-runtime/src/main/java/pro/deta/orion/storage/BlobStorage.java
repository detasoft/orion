package pro.deta.orion.storage;

import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.util.VcResource;
import pro.deta.orion.util.VcRevision;

public abstract class BlobStorage
        extends VcResource<BlobStorage.BlobStorageReadOperations, BlobStorage.BlobStorageUpdateOperations> {
    protected BlobStorage(OrionEventManager eventManager) {
        super(eventManager);
    }

    public final byte[] readBytes(VcRevision.ReadTarget revision) {
        return withRead(revision).read(BlobStorageReadOperations::readContent);
    }

    public interface BlobStorageReadOperations {
        byte[] readContent();
    }

    public abstract static class BlobStorageUpdateOperations extends VcUpdateOperations {
        public abstract void write(byte[] bytes);
    }
}
