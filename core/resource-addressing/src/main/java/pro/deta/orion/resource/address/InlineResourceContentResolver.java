package pro.deta.orion.resource.address;

import java.nio.charset.StandardCharsets;

public final class InlineResourceContentResolver implements ResourceAddressResolver<ResourceContent> {
    @Override
    public Class<ResourceContent> targetType() {
        return ResourceContent.class;
    }

    @Override
    public boolean supports(ResourceExpression expression, ResourceResolutionContext context) {
        return expression.hasScheme(ResourceScheme.CONTENT);
    }

    @Override
    public ResourceContent resolve(ResourceExpression expression, ResourceResolutionContext context) {
        String value = context.resolve(expression, String.class);
        return new ResourceContent(expression.raw(), value.getBytes(StandardCharsets.UTF_8));
    }
}
