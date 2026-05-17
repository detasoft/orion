package pro.deta.orion.resource.address;

import java.util.Map;

public final class ReferenceResolver {
    private final ResourceResolver resolver;

    public ReferenceResolver(ResourceResolver resolver) {
        if (resolver == null) {
            throw new IllegalArgumentException("Resource resolver must not be null");
        }
        this.resolver = resolver;
    }

    public static ReferenceResolver standard() {
        return new ReferenceResolver(ResourceResolver.standard());
    }

    public static ReferenceResolver standard(Map<String, String> environment) {
        return new ReferenceResolver(ResourceResolver.standard(environment));
    }

    public ImmutableReference resolveLocation(String reference) {
        return resolveLocation(parse(reference, "Reference location must not be empty"));
    }

    public ImmutableReference resolveLocation(String reference, String emptyMessage) {
        return resolveLocation(parse(reference, emptyMessage));
    }

    public ImmutableReference resolveLocation(ResourceExpression expression) {
        if (expression.hasScheme(ResourceScheme.CONTENT)) {
            return resolver.resolve(expression, ImmutableReference.class);
        }
        return resolver.resolve(expression, MutableReference.class);
    }

    private static ResourceExpression parse(String reference, String emptyMessage) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException(emptyMessage);
        }
        return ResourceExpression.parse(reference);
    }
}
