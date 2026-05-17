package pro.deta.orion.resource.address;

public final class S3ObjectLocationResolver implements ResourceAddressResolver<S3ObjectLocation> {
    @Override
    public Class<S3ObjectLocation> targetType() {
        return S3ObjectLocation.class;
    }

    @Override
    public boolean supports(ResourceExpression expression, ResourceResolutionContext context) {
        return expression.hasScheme(ResourceScheme.S3);
    }

    @Override
    public S3ObjectLocation resolve(ResourceExpression expression, ResourceResolutionContext context) {
        String value;
        if (expression.hasNested()) {
            value = context.resolve(expression.nested(), String.class);
        } else {
            value = expression.addressText();
        }
        return fromValue(value, expression.path(), expression);
    }

    private static S3ObjectLocation fromValue(String value, String path, ResourceExpression source) {
        String object = value;
        if (object.startsWith("s3:")) {
            object = object.substring("s3:".length());
        }
        object = ResourceAddressSupport.stripSchemeSlashes(object);
        if (object.isBlank()) {
            throw new IllegalArgumentException("S3 address must contain a bucket: " + source.raw());
        }
        int slash = object.indexOf('/');
        String bucket = slash < 0 ? object : object.substring(0, slash);
        String key = slash < 0 ? "" : object.substring(slash + 1);
        key = ResourceAddressSupport.appendPath(key, path);
        return new S3ObjectLocation(bucket, key, source.parameters());
    }
}
