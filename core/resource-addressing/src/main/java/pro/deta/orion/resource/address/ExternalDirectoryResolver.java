package pro.deta.orion.resource.address;

import java.nio.file.Path;

public final class ExternalDirectoryResolver implements ResourceAddressResolver<ExternalDirectory> {
    @Override
    public Class<ExternalDirectory> targetType() {
        return ExternalDirectory.class;
    }

    @Override
    public boolean supports(ResourceExpression expression, ResourceResolutionContext context) {
        return expression.hasEmptyScheme() || expression.hasScheme(ResourceScheme.FILE);
    }

    @Override
    public ExternalDirectory resolve(ResourceExpression expression, ResourceResolutionContext context) {
        Path path = context.resolve(expression, Path.class);
        return new ExternalDirectory(path);
    }
}
