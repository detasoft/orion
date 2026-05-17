package pro.deta.orion.resource.reference.resolver;

import pro.deta.orion.resource.reference.ResourceAddress;
import pro.deta.orion.resource.reference.ResourceCapabilityResolver;
import pro.deta.orion.resource.reference.ResourceReferenceResolutionException;
import pro.deta.orion.resource.reference.ResourceReferenceScope;
import pro.deta.orion.resource.reference.ResourceScheme;

public final class S3ObjectLocationResolver implements ResourceCapabilityResolver<S3ObjectLocation> {
    @Override
    public Class<S3ObjectLocation> targetType() {
        return S3ObjectLocation.class;
    }

    @Override
    public boolean supports(ResourceAddress address, ResourceReferenceScope scope) {
        return address.hasScheme(ResourceScheme.S3);
    }

    @Override
    public S3ObjectLocation resolve(ResourceAddress address, ResourceReferenceScope scope) {
        String object = stripSchemeSlashes(address.body());
        if (object.isBlank()) {
            throw new ResourceReferenceResolutionException(
                    "S3 resource reference must contain a bucket: " + address.raw());
        }
        int slash = object.indexOf('/');
        String bucket = slash < 0 ? object : object.substring(0, slash);
        String key = slash < 0 ? "" : object.substring(slash + 1);
        return new S3ObjectLocation(bucket, key, address.parameters());
    }

    private static String stripSchemeSlashes(String value) {
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }
}
