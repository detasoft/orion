package pro.deta.orion.resource.address;

import java.nio.file.Path;

public final class FilePathResolver implements ResourceAddressResolver<Path> {
    @Override
    public Class<Path> targetType() {
        return Path.class;
    }

    @Override
    public boolean supports(ResourceExpression expression, ResourceResolutionContext context) {
        return expression.hasEmptyScheme() || expression.hasScheme(ResourceScheme.FILE);
    }

    @Override
    public Path resolve(ResourceExpression expression, ResourceResolutionContext context) {
        String value;
        if (expression.hasNested()) {
            value = context.resolve(expression.nested(), String.class);
        } else {
            value = expression.directValue();
        }
        return Path.of(ResourceAddressSupport.appendPath(value, expression.path()));
    }
}
