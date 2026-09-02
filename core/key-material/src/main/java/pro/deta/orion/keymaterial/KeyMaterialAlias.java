package pro.deta.orion.keymaterial;

public record KeyMaterialAlias(String value) {
    public KeyMaterialAlias {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Key material alias must not be empty");
        }
    }
}
