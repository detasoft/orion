package pro.deta.orion.resource.address;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

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
        if (value.startsWith(ResourceAddressConstants.BASE64_CONTENT_PREFIX)) {
            return new ResourceContent(
                    expression.raw(),
                    Base64.getDecoder().decode(value.substring(ResourceAddressConstants.BASE64_CONTENT_PREFIX.length())));
        }
        return new ResourceContent(expression.raw(), value.getBytes(StandardCharsets.UTF_8));
    }
}
