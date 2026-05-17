package pro.deta.orion.resource.address;

public record ResourceAddress(String raw, ResourceExpression expression) {
    public ResourceAddress {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Resource address must not be empty");
        }
        if (expression == null) {
            throw new IllegalArgumentException("Resource expression must not be null");
        }
    }

    public static ResourceAddress parse(String raw) {
        return new ResourceAddress(raw, ResourceExpression.parse(raw));
    }
}
