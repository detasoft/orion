package pro.deta.orion.keymaterial;

import pro.deta.orion.resource.address.LocalMutableReference;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public class LocalKeyMaterialContentStore implements KeyMaterialContentStore {
    private final LocalMutableReference reference;
    private final ReferenceKeyMaterialContentStore delegate;

    public LocalKeyMaterialContentStore(Path path) {
        this.reference = new LocalMutableReference(path);
        this.delegate = new ReferenceKeyMaterialContentStore(reference);
    }

    public Path path() {
        return reference.path();
    }

    @Override
    public Optional<KeyMaterialSnapshot> read() throws IOException {
        return delegate.read();
    }

    @Override
    public String write(byte[] bytes, String expectedVersion) throws IOException {
        return delegate.write(bytes, expectedVersion);
    }
}
