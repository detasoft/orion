package pro.deta.orion.storage;

import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.util.VcChangeDescription;
import pro.deta.orion.util.VcResource;
import pro.deta.orion.util.VcRevision;

import java.util.Objects;

public abstract class BlobStorage extends VcResource {
    protected BlobStorage(OrionEventManager eventManager) {
        super(eventManager);
    }

    protected abstract byte[] readContent(VcRevision.ReadTarget revision);

    protected abstract void write(byte[] content);

    public final VcRead<BlobStorageReadOperations> withRead(VcRevision.ReadTarget revision) {
        return new VcRead<>(new BlobStorageReadOperations(Objects.requireNonNull(revision, "revision")));
    }

    public final byte[] readBytes(VcRevision.ReadTarget revision) {
        return withRead(revision).read(BlobStorageReadOperations::readContent);
    }

    public final VcModification<BlobStorageUpdateOperations> withModification(VcChangeDescription description) {
        return new VcModification<>(description, createUpdateOperation());
    }

    protected BlobStorageUpdateOperations createUpdateOperation() {
        return new BlobStorageUpdateOperations();
    }

    public final class BlobStorageReadOperations {
        private final VcRevision.ReadTarget revision;

        private BlobStorageReadOperations(VcRevision.ReadTarget revision) {
            this.revision = revision;
        }

        public byte[] readContent() {
            return BlobStorage.this.readContent(revision);
        }
    }

    public class BlobStorageUpdateOperations {
        protected BlobStorageUpdateOperations() {}

        public void write(byte[] bytes) {
            BlobStorage.this.write(bytes);
        }

    }
}
