package pro.deta.orion.keymaterial;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

public class InMemoryKeyMaterialContentStore implements KeyMaterialContentStore {
    private byte[] bytes;
    private long version;

    @Override
    public synchronized Optional<KeyMaterialSnapshot> read() {
        if (bytes == null) {
            return Optional.empty();
        }
        return Optional.of(new KeyMaterialSnapshot(bytes, versionString()));
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
