package pro.deta.orion.resource.reference;

import java.util.ArrayList;
import java.util.List;

public final class ResourceResolverRegistry {
    private final List<ResourceCapabilityResolver<?>> resolvers;

    private ResourceResolverRegistry(List<ResourceCapabilityResolver<?>> resolvers) {
        this.resolvers = List.copyOf(resolvers);
    }

    public static Builder builder() {
        return new Builder();
    }

    List<ResourceCapabilityResolver<?>> resolvers() {
        return resolvers;
    }

    public static final class Builder {
        private final List<ResourceCapabilityResolver<?>> resolvers = new ArrayList<>();

        public Builder withDefaults() {
            return this;
        }

        public Builder add(ResourceCapabilityResolver<?> resolver) {
            resolvers.add(resolver);
            return this;
        }

        public ResourceResolverRegistry build() {
            return new ResourceResolverRegistry(resolvers);
        }
    }
}
