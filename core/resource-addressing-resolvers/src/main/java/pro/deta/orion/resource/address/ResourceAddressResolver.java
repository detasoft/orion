package pro.deta.orion.resource.address;

public interface ResourceAddressResolver<T> {
    Class<T> targetType();

    boolean supports(ResourceExpression expression, ResourceResolutionContext context);

    T resolve(ResourceExpression expression, ResourceResolutionContext context);
}
