package pro.deta.orion.resource.reference.resolver;

import pro.deta.orion.resource.reference.ResourceAddress;
import pro.deta.orion.resource.reference.ResourceCapabilityResolver;
import pro.deta.orion.resource.reference.ResourceContent;
import pro.deta.orion.resource.reference.ResourceReferenceResolutionException;
import pro.deta.orion.resource.reference.ResourceReferenceScope;
import pro.deta.orion.resource.reference.ResourceScheme;

import java.util.Optional;

public final class S3ResourceContentResolver implements ResourceCapabilityResolver<ResourceContent> {
    private final S3ObjectContentReader contentReader;
    private final S3ObjectLocationResolver locationResolver = new S3ObjectLocationResolver();

    public S3ResourceContentResolver(S3ObjectContentReader contentReader) {
        if (contentReader == null) {
            throw new IllegalArgumentException("S3 content reader must not be null");
        }
        this.contentReader = contentReader;
    }

    @Override
    public Class<ResourceContent> targetType() {
        return ResourceContent.class;
    }

    @Override
    public boolean supports(ResourceAddress address, ResourceReferenceScope scope) {
        return address.hasScheme(ResourceScheme.S3);
    }

    @Override
    public ResourceContent resolve(ResourceAddress address, ResourceReferenceScope scope) {
        S3ObjectLocation location = locationResolver.resolve(address, scope);
        Optional<byte[]> bytes = contentReader.read(location);
        if (bytes.isEmpty()) {
            throw new ResourceReferenceResolutionException("S3 resource does not exist: " + address.raw());
        }
        return new ResourceContent(address.raw(), bytes.get());
    }
}
