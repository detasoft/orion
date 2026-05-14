package pro.deta.orion.resource.address;

import java.util.Optional;

public final class S3ResourceContentResolver implements ResourceAddressResolver<ResourceContent> {
    private final S3ObjectContentReader contentReader;

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
    public boolean supports(ResourceExpression expression, ResourceResolutionContext context) {
        return expression.hasScheme(ResourceScheme.S3);
    }

    @Override
    public ResourceContent resolve(ResourceExpression expression, ResourceResolutionContext context) {
        S3ObjectLocation location = context.resolve(expression, S3ObjectLocation.class);
        Optional<byte[]> bytes = contentReader.read(location);
        if (bytes.isEmpty()) {
            throw new IllegalArgumentException("S3 resource does not exist: " + expression.raw());
        }
        return new ResourceContent(expression.raw(), bytes.get());
    }
}
