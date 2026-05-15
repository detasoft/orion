package pro.deta.orion.resource.reference;

public record ResourceReference(String raw, ReferenceNode root) {
    public ResourceReference {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Resource reference must not be empty");
        }
        if (root == null) {
            throw new IllegalArgumentException("Resource reference root must not be null");
        }
    }

    public static ResourceReference parse(String raw) {
        return ResourceReferenceParser.parse(raw);
    }
}
