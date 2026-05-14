package pro.deta.orion.resource.address;

import java.util.Map;

public final class EnvironmentStringResolver implements ResourceAddressResolver<String> {
    private final Map<String, String> environment;

    public EnvironmentStringResolver() {
        this(System.getenv());
    }

    public EnvironmentStringResolver(Map<String, String> environment) {
        this.environment = environment == null ? Map.of() : Map.copyOf(environment);
    }

    @Override
    public Class<String> targetType() {
        return String.class;
    }

    @Override
    public boolean supports(ResourceExpression expression, ResourceResolutionContext context) {
        return expression.hasScheme(ResourceScheme.ENV) && !expression.hasNested();
    }

    @Override
    public String resolve(ResourceExpression expression, ResourceResolutionContext context) {
        String name = expression.directValue();
        String value = environment.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Environment variable is not set: " + name);
        }
        return ResourceAddressSupport.appendPath(value, expression.path());
    }
}
