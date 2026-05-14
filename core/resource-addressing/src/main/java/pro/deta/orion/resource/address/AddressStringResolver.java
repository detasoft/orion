package pro.deta.orion.resource.address;

public final class AddressStringResolver implements ResourceAddressResolver<String> {
    @Override
    public Class<String> targetType() {
        return String.class;
    }

    @Override
    public boolean supports(ResourceExpression expression, ResourceResolutionContext context) {
        return !expression.hasScheme(ResourceScheme.ENV) && !expression.hasScheme(ResourceScheme.CONTENT);
    }

    @Override
    public String resolve(ResourceExpression expression, ResourceResolutionContext context) {
        if (expression.hasNested()) {
            String value = context.resolve(expression.nested(), String.class);
            return ResourceAddressSupport.appendPath(value, expression.path());
        }
        if (expression.hasEmptyScheme() || expression.hasScheme(ResourceScheme.FILE)) {
            return ResourceAddressSupport.appendPath(expression.directValue(), expression.path());
        }
        return expression.addressText() + ResourceAddressSupport.queryString(expression.parameters());
    }
}
