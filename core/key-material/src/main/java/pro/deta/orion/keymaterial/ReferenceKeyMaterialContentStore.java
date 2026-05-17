package pro.deta.orion.keymaterial;

import pro.deta.orion.resource.address.ImmutableReference;
import pro.deta.orion.resource.address.MutableReference;
import pro.deta.orion.resource.address.MutableReferenceConflictException;
import pro.deta.orion.resource.address.ResourceContent;

import java.io.IOException;
import java.util.Optional;

final class ReferenceKeyMaterialContentStore implements KeyMaterialContentStore {
    private final ImmutableReference reference;

    ReferenceKeyMaterialContentStore(ImmutableReference reference) {
        if (reference == null) {
            throw new IllegalArgumentException("Reference must not be null");
        }
        this.reference = reference;
    }

    @Override
    public Optional<KeyMaterialSnapshot> read() throws IOException {
        Optional<ResourceContent> snapshot = reference.read();
        if (snapshot.isEmpty()) {
            return Optional.empty();
        }
        ResourceContent value = snapshot.get();
        String version = value.version()
                .orElseThrow(() -> new IOException("Reference did not provide a version"));
        return Optional.of(new KeyMaterialSnapshot(value.bytes(), version));
    }

    @Override
    public String write(byte[] bytes, String expectedVersion) throws IOException {
        if (!(reference instanceof MutableReference mutableReference)) {
            throw new IOException("Reference is read-only");
        }
        try {
            return mutableReference.write(bytes, expectedVersion);
        } catch (MutableReferenceConflictException e) {
            throw new KeyMaterialStoreConflictException(e.getMessage(), e);
        }
    }
}
