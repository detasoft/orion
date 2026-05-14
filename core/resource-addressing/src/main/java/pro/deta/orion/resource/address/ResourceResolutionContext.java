package pro.deta.orion.resource.address;

public interface ResourceResolutionContext {
    <T> T resolve(ResourceExpression expression, Class<T> targetType);

    default <T> T resolve(String raw, Class<T> targetType) {
        return resolve(ResourceExpression.parse(raw), targetType);
    }
}
