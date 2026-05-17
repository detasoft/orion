package pro.deta.orion.resource.address;

import java.util.Map;

public final class GitRepositoryLocationResolver implements ResourceAddressResolver<GitRepositoryLocation> {
    @Override
    public Class<GitRepositoryLocation> targetType() {
        return GitRepositoryLocation.class;
    }

    @Override
    public boolean supports(ResourceExpression expression, ResourceResolutionContext context) {
        return expression.hasEmptyScheme()
                || expression.hasScheme(ResourceScheme.FILE)
                || expression.hasScheme(ResourceScheme.GIT)
                || expression.hasScheme(ResourceScheme.GIT_FILE)
                || expression.hasScheme(ResourceScheme.GIT_SSH)
                || expression.hasScheme(ResourceScheme.GIT_HTTP)
                || expression.hasScheme(ResourceScheme.GIT_HTTPS)
                || expression.hasScheme(ResourceScheme.SSH)
                || expression.hasScheme(ResourceScheme.HTTP)
                || expression.hasScheme(ResourceScheme.HTTPS)
                || expression.hasScheme(ResourceScheme.S3);
    }

    @Override
    public GitRepositoryLocation resolve(ResourceExpression expression, ResourceResolutionContext context) {
        if (expression.hasPath()) {
            throw new IllegalArgumentException(
                    "Git repository address must not contain a repository-internal path: " + expression.raw());
        }
        if (expression.hasNested()) {
            String nested = context.resolve(expression.nested(), String.class);
            return fromValue(nested, expression.parameters());
        }
        if (expression.hasScheme(ResourceScheme.GIT_FILE)) {
            return new GitRepositoryLocation(GitRepositoryLocation.Kind.LOCAL, expression.directValue(), expression.parameters());
        }
        if (expression.hasScheme(ResourceScheme.GIT_SSH)) {
            return new GitRepositoryLocation(GitRepositoryLocation.Kind.SSH, expression.directValue(), expression.parameters());
        }
        if (expression.hasScheme(ResourceScheme.GIT_HTTP)) {
            return new GitRepositoryLocation(GitRepositoryLocation.Kind.HTTP, expression.directValue(), expression.parameters());
        }
        if (expression.hasScheme(ResourceScheme.GIT_HTTPS)) {
            return new GitRepositoryLocation(GitRepositoryLocation.Kind.HTTPS, expression.directValue(), expression.parameters());
        }
        if (expression.hasScheme(ResourceScheme.FILE)) {
            return new GitRepositoryLocation(GitRepositoryLocation.Kind.LOCAL, expression.directValue(), expression.parameters());
        }
        return fromValue(expression.addressText(), expression.parameters());
    }

    private static GitRepositoryLocation fromValue(String value, Map<String, String> parameters) {
        ResourceExpression expression = ResourceExpression.parse(value);
        if (expression.hasScheme(ResourceScheme.FILE)) {
            return new GitRepositoryLocation(GitRepositoryLocation.Kind.LOCAL, expression.directValue(), parameters);
        }
        if (expression.hasScheme(ResourceScheme.SSH)) {
            return new GitRepositoryLocation(GitRepositoryLocation.Kind.SSH, expression.addressText(), parameters);
        }
        if (expression.hasScheme(ResourceScheme.HTTP)) {
            return new GitRepositoryLocation(GitRepositoryLocation.Kind.HTTP, expression.addressText(), parameters);
        }
        if (expression.hasScheme(ResourceScheme.HTTPS)) {
            return new GitRepositoryLocation(GitRepositoryLocation.Kind.HTTPS, expression.addressText(), parameters);
        }
        if (expression.hasScheme(ResourceScheme.S3)) {
            return new GitRepositoryLocation(GitRepositoryLocation.Kind.S3, expression.addressText(), parameters);
        }
        if (expression.hasEmptyScheme()) {
            return new GitRepositoryLocation(GitRepositoryLocation.Kind.LOCAL, expression.directValue(), parameters);
        }
        return new GitRepositoryLocation(GitRepositoryLocation.Kind.UNKNOWN, value, parameters);
    }
}
