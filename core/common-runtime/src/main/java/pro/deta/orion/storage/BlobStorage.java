package pro.deta.orion.storage;

import pro.deta.orion.util.VcChangeDescription;
import pro.deta.orion.util.VcResource;
import pro.deta.orion.util.VcRevision;


public abstract class BlobStorage extends VcResource {
    public final byte[] read(String revision) {
        return read(new VcRevision.Stored(revision), this::readContent);
    }

    protected abstract byte[] readContent();

    protected abstract void write(byte[] content);

    public final VcModification<BlobStorageOperations> withModification(VcChangeDescription vcChangeDescription) {
        return new VcModification<BlobStorageOperations>(vcChangeDescription, new BlobStorageOperations());
    }

    public final class BlobStorageOperations {
        private BlobStorageOperations() {}

        public void write(byte[] bytes) {
            BlobStorage.this.write(bytes);
        }
    }
}
