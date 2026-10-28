package pro.deta.orion.keymaterial;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

public class InMemoryKeyMaterialContentSource implements KeyMaterialContentSource {
    private byte[] bytes;
    private long version;

    @Override
    public synchronized Optional<String> revision() {
        return bytes == null ? Optional.empty() : Optional.of(versionString());
    }

    @Override
    public synchronized byte[] read(String revision) throws IOException {
        Objects.requireNonNull(revision, "revision");
        if (bytes == null || !revision.equals(versionString())) {
            throw new KeyMaterialStoreConflictException("Key material revision is no longer available: " + revision);
        }
        return Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public synchronized String write(byte[] newBytes, String expectedVersion) throws IOException {
        if (newBytes == null) {
            throw new IllegalArgumentException("Key material bytes must not be null");
        }
        String currentVersion = bytes == null ? null : versionString();
        if (!matchesExpectedVersion(currentVersion, expectedVersion)) {
            throw new KeyMaterialStoreConflictException("Key material store changed before save");
        }
        bytes = Arrays.copyOf(newBytes, newBytes.length);
        version++;
        return versionString();
    }

    private static boolean matchesExpectedVersion(String currentVersion, String expectedVersion) {
        if (currentVersion == null) {
            return expectedVersion == null;
        }
        return currentVersion.equals(expectedVersion);
    }

    private String versionString() {
        return Long.toString(version);
    }
}
