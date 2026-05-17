package pro.deta.orion.resource.reference.resolver;

import pro.deta.orion.resource.reference.ResourceAddress;
import pro.deta.orion.resource.reference.ResourceCapabilityResolver;
import pro.deta.orion.resource.reference.ResourceReferenceScope;
import pro.deta.orion.resource.reference.ResourceScheme;

public final class GitRepositoryLocationResolver implements ResourceCapabilityResolver<GitRepositoryLocation> {
    @Override
    public Class<GitRepositoryLocation> targetType() {
        return GitRepositoryLocation.class;
    }

    @Override
    public boolean supports(ResourceAddress address, ResourceReferenceScope scope) {
        return address.scheme().isEmpty()
                || address.hasScheme(ResourceScheme.FILE)
                || address.hasScheme(ResourceScheme.GIT)
                || address.hasScheme(ResourceScheme.GIT_FILE)
                || address.hasScheme(ResourceScheme.GIT_SSH)
                || address.hasScheme(ResourceScheme.GIT_HTTP)
                || address.hasScheme(ResourceScheme.GIT_HTTPS)
                || address.hasScheme(ResourceScheme.SSH)
                || address.hasScheme(ResourceScheme.HTTP)
                || address.hasScheme(ResourceScheme.HTTPS)
                || address.hasScheme(ResourceScheme.S3);
    }

    @Override
    public GitRepositoryLocation resolve(ResourceAddress address, ResourceReferenceScope scope) {
        ResourceScheme scheme = address.scheme();
        GitRepositoryLocation.Kind kind = kind(scheme);
        return new GitRepositoryLocation(kind, location(address, kind), address.parameters());
    }

    private static GitRepositoryLocation.Kind kind(ResourceScheme scheme) {
        if (scheme.isEmpty() || scheme.equals(ResourceScheme.FILE) || scheme.equals(ResourceScheme.GIT_FILE)) {
            return GitRepositoryLocation.Kind.LOCAL;
        }
        if (scheme.equals(ResourceScheme.SSH) || scheme.equals(ResourceScheme.GIT_SSH)) {
            return GitRepositoryLocation.Kind.SSH;
        }
        if (scheme.equals(ResourceScheme.HTTP) || scheme.equals(ResourceScheme.GIT_HTTP)) {
            return GitRepositoryLocation.Kind.HTTP;
        }
        if (scheme.equals(ResourceScheme.HTTPS) || scheme.equals(ResourceScheme.GIT_HTTPS)) {
            return GitRepositoryLocation.Kind.HTTPS;
        }
        if (scheme.equals(ResourceScheme.S3)) {
            return GitRepositoryLocation.Kind.S3;
        }
        return GitRepositoryLocation.Kind.UNKNOWN;
    }

    private static String location(ResourceAddress address, GitRepositoryLocation.Kind kind) {
        if (address.hasScheme(ResourceScheme.GIT_SSH)) {
            return "ssh:" + address.body();
        }
        if (address.hasScheme(ResourceScheme.GIT_HTTP)) {
            return "http:" + address.body();
        }
        if (address.hasScheme(ResourceScheme.GIT_HTTPS)) {
            return "https:" + address.body();
        }
        if (kind == GitRepositoryLocation.Kind.LOCAL || address.scheme().value().startsWith("git+")) {
            return address.body();
        }
        return address.addressWithoutParameters();
    }
}
