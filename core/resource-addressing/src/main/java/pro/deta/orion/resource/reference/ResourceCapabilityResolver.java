package pro.deta.orion.resource.reference;

public interface ResourceCapabilityResolver<T> {
    Class<T> targetType();

    boolean supports(ResourceAddress address, ResourceReferenceScope scope);

    T resolve(ResourceAddress address, ResourceReferenceScope scope);
}
