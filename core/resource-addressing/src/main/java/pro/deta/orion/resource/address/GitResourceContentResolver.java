package pro.deta.orion.resource.address;

import java.util.Optional;

public final class GitResourceContentResolver implements ResourceAddressResolver<ResourceContent> {
    private final GitRepositoryContentReader contentReader;

    public GitResourceContentResolver(GitRepositoryContentReader contentReader) {
        if (contentReader == null) {
            throw new IllegalArgumentException("Git content reader must not be null");
        }
        this.contentReader = contentReader;
    }

    @Override
    public Class<ResourceContent> targetType() {
        return ResourceContent.class;
    }

    @Override
    public boolean supports(ResourceExpression expression, ResourceResolutionContext context) {
        return expression.hasPath()
                && (expression.hasScheme(ResourceScheme.GIT)
                || expression.hasScheme(ResourceScheme.GIT_FILE)
                || expression.hasScheme(ResourceScheme.GIT_SSH)
                || expression.hasScheme(ResourceScheme.GIT_HTTP)
                || expression.hasScheme(ResourceScheme.GIT_HTTPS));
    }

    @Override
    public ResourceContent resolve(ResourceExpression expression, ResourceResolutionContext context) {
        GitRepositoryLocation repository = context.resolve(expression.withoutPath(), GitRepositoryLocation.class);
        String ref = expression.parameter("ref", "branch", null);
        Optional<byte[]> bytes = contentReader.read(repository, expression.path(), ref);
        if (bytes.isEmpty()) {
            throw new IllegalArgumentException("Git resource does not exist: " + expression.raw());
        }
        return new ResourceContent(expression.raw(), bytes.get());
    }
}
