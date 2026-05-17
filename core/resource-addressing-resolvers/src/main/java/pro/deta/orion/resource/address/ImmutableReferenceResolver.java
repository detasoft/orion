package pro.deta.orion.resource.address;

import java.nio.charset.StandardCharsets;

public final class ImmutableReferenceResolver implements ResourceAddressResolver<ImmutableReference> {
    @Override
    public Class<ImmutableReference> targetType() {
        return ImmutableReference.class;
    }

    @Override
    public boolean supports(ResourceExpression expression, ResourceResolutionContext context) {
        return expression.hasEmptyScheme()
                || expression.hasScheme(ResourceScheme.ENV)
                || expression.hasScheme(ResourceScheme.CONTENT);
    }

    @Override
    public ImmutableReference resolve(ResourceExpression expression, ResourceResolutionContext context) {
        if (expression.hasScheme(ResourceScheme.CONTENT)) {
            return new ResolvedImmutableReference(context.resolve(expression, ResourceContent.class));
        }
        String value = context.resolve(expression, String.class);
        return new ResolvedImmutableReference(
                new ResourceContent(expression.raw(), value.getBytes(StandardCharsets.UTF_8)));
    }
}
