package pro.deta.orion.keymaterial;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.storage.BlobStorage;
import pro.deta.orion.storage.InMemoryBlobStorage;
import pro.deta.orion.storage.BlobStorageConflictException;
import pro.deta.orion.util.VcChangeDescription;
import pro.deta.orion.util.VcModificationException;
import pro.deta.orion.util.VcRevision;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;

final class KeyMaterialTestRuntime implements AfterEachCallback {
    final OrionEventManager events = new OrionEventManager() {
        @Override
        public void publish(pro.deta.orion.event.type.OrionEvent event) {
            if (isRunning()) super.publish(event);
        }
    };

    static byte[] emptyContent() {
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password());
                KeyMaterialCodec codec = new KeyMaterialCodec(options)) {
            return codec.encode(codec.create());
        } catch (IOException | GeneralSecurityException failure) {
            throw new IllegalStateException("Cannot create test material", failure);
        }
    }

    static String revision(BlobStorage store) {
        try (BlobStorage.BlobStorageOperations operation = store.open(new VcRevision.Latest())) {
            return operation.revision().value();
        }
    }

    static byte[] read(BlobStorage store) {
        return store.readBytes(new VcRevision.Latest());
    }

    static byte[] read(BlobStorage store, String revision) {
        return store.readBytes(new VcRevision.Stored(revision));
    }

    static String write(BlobStorage store, String expected, byte[] content) throws IOException {
        VcRevision.ReadTarget target = expected == null ? new VcRevision.Latest() : new VcRevision.Stored(expected);
        try (BlobStorage.BlobStorageOperations operation = store.open(target)) {
            operation.write(content);
            return operation.commit(VcChangeDescription.EMPTY).value();
        } catch (VcModificationException conflict) {
            throw new BlobStorageConflictException("Blob changed before save", conflict);
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    static class TestBlobStorage extends InMemoryBlobStorage implements AutoCloseable {
        TestBlobStorage(OrionEventManager events) {
            super(emptyContent(), false, events);
        }

        @Override
        protected BlobStorageOperations createOperation(VcRevision.ReadTarget target) {
            BlobStorageOperations delegate = super.createOperation(target);
            return new BlobStorageOperations(delegate.revision()) {
                @Override
                public byte[] readContent() {
                    return readSnapshot(delegate);
                }

                @Override
                public void write(byte[] content) {
                    delegate.write(content);
                }

                @Override
                protected VcRevision.Stored commitChanges(VcChangeDescription description) {
                    try {
                        return commitSnapshot(delegate, description);
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                }

                @Override
                public void close() {
                    delegate.close();
                }
            };
        }

        protected byte[] readSnapshot(BlobStorageOperations operation) {
            return operation.readContent();
        }

        protected VcRevision.Stored commitSnapshot(BlobStorageOperations operation, VcChangeDescription description)
                throws IOException {
            return operation.commit(description);
        }

        @Override
        public void close() {}
    }

    @Override
    public void afterEach(ExtensionContext context) {
        if (events.isRunning()) events.onStop();
    }
}
