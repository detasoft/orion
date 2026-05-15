package pro.deta.orion.resource.reference;

import java.util.Map;
import java.util.Optional;

public record ResourceReferenceScope(
        Map<String, String> environment,
        Map<String, String> context,
        int maxDepth) {

    private static final int DEFAULT_MAX_DEPTH = 16;

    public ResourceReferenceScope {
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        context = context == null ? Map.of() : Map.copyOf(context);
        if (maxDepth <= 0) {
            maxDepth = DEFAULT_MAX_DEPTH;
        }
    }

    public static ResourceReferenceScope empty() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<String> variable(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        if (name.contains(".")) {
            return Optional.ofNullable(context.get(name));
        }
        String environmentValue = environment.get(name);
        if (environmentValue != null) {
            return Optional.of(environmentValue);
        }
        return Optional.ofNullable(context.get(name));
    }

    public static final class Builder {
        private Map<String, String> environment = Map.of();
        private Map<String, String> context = Map.of();
        private int maxDepth = DEFAULT_MAX_DEPTH;

        public Builder environment(Map<String, String> environment) {
            this.environment = environment;
            return this;
        }

        public Builder context(Map<String, String> context) {
            this.context = context;
            return this;
        }

        public Builder maxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
            return this;
        }

        public ResourceReferenceScope build() {
            return new ResourceReferenceScope(environment, context, maxDepth);
        }
    }
}
