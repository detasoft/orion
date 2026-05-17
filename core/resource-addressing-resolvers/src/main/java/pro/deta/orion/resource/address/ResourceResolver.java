package pro.deta.orion.resource.address;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ResourceResolver implements ResourceResolutionContext {
    private final List<ResourceAddressResolver<?>> resolvers;

    public ResourceResolver(Collection<? extends ResourceAddressResolver<?>> resolvers) {
        if (resolvers == null || resolvers.isEmpty()) {
            throw new IllegalArgumentException("Resource resolvers must not be empty");
        }
        this.resolvers = List.copyOf(resolvers);
    }

    public static ResourceResolver standard() {
        return standard(System.getenv());
    }

    public static ResourceResolver standard(Map<String, String> environment) {
        List<ResourceAddressResolver<?>> resolvers = new ArrayList<>();
        resolvers.add(new EnvironmentStringResolver(environment));
        resolvers.add(new ContentStringResolver());
        resolvers.add(new AddressStringResolver());
        resolvers.add(new FilePathResolver());
        resolvers.add(new ExternalDirectoryResolver());
        resolvers.add(new GitRepositoryLocationResolver());
        resolvers.add(new S3ObjectLocationResolver());
        resolvers.add(new InlineResourceContentResolver());
        resolvers.add(new ImmutableReferenceResolver());
        resolvers.add(new MutableReferenceResolver());
        return new ResourceResolver(resolvers);
    }

    public static List<Class<?>> standardTargetTypes() {
        return ResourceAddressTargetType.standardTypes();
    }

    public <T> T resolve(String raw, Class<T> targetType) {
        return resolve(ResourceExpression.parse(raw), targetType);
    }

    public <T> T resolve(ResourceAddress address, Class<T> targetType) {
        Objects.requireNonNull(address, "address");
        return resolve(address.expression(), targetType);
    }

    @Override
    public <T> T resolve(ResourceExpression expression, Class<T> targetType) {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(targetType, "targetType");

        ResourceAddressResolver<T> matchingResolver = null;
        for (ResourceAddressResolver<?> resolver : resolvers) {
            if (!targetType.equals(resolver.targetType()) || !resolver.supports(expression, this)) {
                continue;
            }
            if (matchingResolver != null) {
                throw new IllegalStateException(
                        "Ambiguous resource resolvers for " + targetType.getName() + ": " + expression.raw());
            }
            matchingResolver = cast(resolver);
        }
        if (matchingResolver == null) {
            throw new IllegalArgumentException(
                    "No resource resolver for " + targetType.getName() + ": " + expression.raw());
        }
        return matchingResolver.resolve(expression, this);
    }

    @SuppressWarnings("unchecked")
    private static <T> ResourceAddressResolver<T> cast(ResourceAddressResolver<?> resolver) {
        return (ResourceAddressResolver<T>) resolver;
    }
}
