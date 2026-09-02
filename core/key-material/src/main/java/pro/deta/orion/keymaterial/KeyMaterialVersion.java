package pro.deta.orion.keymaterial;

public record KeyMaterialVersion(long value) implements Comparable<KeyMaterialVersion> {
    public KeyMaterialVersion {
        if (value < 1) {
            throw new IllegalArgumentException("Key material version must be positive");
        }
    }

    @Override
    public int compareTo(KeyMaterialVersion other) {
        if (other == null) {
            throw new IllegalArgumentException("Key material version must not be null");
        }
        return Long.compare(value, other.value);
    }
}
