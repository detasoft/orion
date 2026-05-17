package pro.deta.orion.resource.address;

import java.nio.file.Path;

public final class MutableReferenceResolver implements ResourceAddressResolver<MutableReference> {
    @Override
    public Class<MutableReference> targetType() {
        return MutableReference.class;
    }

    @Override
    public boolean supports(ResourceExpression expression, ResourceResolutionContext context) {
        return expression.hasEmptyScheme()
                || expression.hasScheme(ResourceScheme.FILE)
                || expression.hasScheme(ResourceScheme.ENV);
    }

    @Override
    public MutableReference resolve(ResourceExpression expression, ResourceResolutionContext context) {
        if (expression.hasScheme(ResourceScheme.ENV) && !expression.hasNested()) {
            String resolvedReference = context.resolve(expression, String.class);
            return context.resolve(ResourceExpression.parse(resolvedReference), MutableReference.class);
        }
        if (expression.hasEmptyScheme() || expression.hasScheme(ResourceScheme.FILE)) {
            Path path = context.resolve(expression, Path.class);
            return new LocalMutableReference(path);
        }
        throw new IllegalArgumentException("Unsupported mutable reference: " + expression.raw());
    }
}
