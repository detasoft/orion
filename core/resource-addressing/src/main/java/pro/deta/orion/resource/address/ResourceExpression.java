package pro.deta.orion.resource.address;

import java.util.Map;
import java.util.Optional;

public record ResourceExpression(
        String raw,
        ResourceScheme scheme,
        String body,
        ResourceExpression nested,
        String path,
        Map<String, String> parameters) {

    public ResourceExpression {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Resource expression must not be empty");
        }
        if (scheme == null) {
            scheme = ResourceScheme.EMPTY;
        }
        if (path == null) {
            path = "";
        }
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    public static ResourceExpression parse(String raw) {
        return ResourceAddressParser.parse(raw);
    }

    public boolean hasScheme(ResourceScheme expected) {
        return scheme.equals(expected);
    }

    public boolean hasEmptyScheme() {
        return scheme.isEmpty();
    }

    public boolean hasNested() {
        return nested != null;
    }

    public Optional<ResourceExpression> nestedExpression() {
        return Optional.ofNullable(nested);
    }

    public boolean hasPath() {
        return !path.isBlank();
    }

    public String parameter(String primary, String fallback, String defaultValue) {
        String value = parameters.get(primary);
        if ((value == null || value.isBlank()) && fallback != null) {
            value = parameters.get(fallback);
        }
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }

    public ResourceExpression withoutPath() {
        return new ResourceExpression(raw, scheme, body, nested, "", parameters);
    }

    public String directValue() {
        if (hasNested()) {
            throw new IllegalStateException("Resource expression is nested: " + raw);
        }
        return hasEmptyScheme() ? raw : body;
    }

    public String addressText() {
        if (hasEmptyScheme()) {
            return body == null ? raw : body;
        }
        if (hasNested()) {
            String suffix = path.isBlank() ? "" : "/" + path;
            return scheme.value() + "(" + nested.raw() + ")" + suffix;
        }
        return scheme.value() + ":" + body;
    }
}
