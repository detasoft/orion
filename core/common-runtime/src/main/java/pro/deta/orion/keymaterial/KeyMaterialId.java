package pro.deta.orion.keymaterial;

/** Identifies one material entry by alias and its positive per-alias version. */
public record KeyMaterialId(String alias, long version) {
    public KeyMaterialId {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("Material alias must not be blank");
        }
        alias = alias.trim();
        if (version <= 0) {
            throw new IllegalArgumentException("Material version must be positive");
        }
    }
}
