package pro.deta.orion.keymaterial;

import java.util.Arrays;

public record KeyMaterialSnapshot(byte[] bytes, String version) {
    public KeyMaterialSnapshot {
        if (bytes == null) {
            throw new IllegalArgumentException("Key material bytes must not be null");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("Key material version must not be empty");
        }
        bytes = Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
}
