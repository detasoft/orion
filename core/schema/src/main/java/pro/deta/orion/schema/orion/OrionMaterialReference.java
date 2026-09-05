package pro.deta.orion.schema.orion;

public record OrionMaterialReference(String alias, long version) {
    public OrionMaterialReference {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("Material alias must not be blank");
        }
        alias = alias.trim();
        if (version <= 0) {
            throw new IllegalArgumentException("Material version must be positive");
        }
    }
}
