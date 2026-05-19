package pro.deta.orion.resource.reference.resolver;

import pro.deta.orion.resource.reference.ResourceAddress;
import pro.deta.orion.resource.reference.ResourceCapabilityResolver;
import pro.deta.orion.resource.reference.ResourceContent;
import pro.deta.orion.resource.reference.ResourceReferenceResolutionException;
import pro.deta.orion.resource.reference.ResourceReferenceScope;
import pro.deta.orion.resource.reference.ResourceScheme;

import java.util.Optional;

public final class GitResourceContentResolver implements ResourceCapabilityResolver<ResourceContent> {
    private final GitRepositoryContentReader contentReader;
    private final GitRepositoryLocationResolver locationResolver = new GitRepositoryLocationResolver();

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
    public boolean supports(ResourceAddress address, ResourceReferenceScope scope) {
        return address.hasScheme(ResourceScheme.GIT)
                || address.hasScheme(ResourceScheme.GIT_FILE)
                || address.hasScheme(ResourceScheme.GIT_SSH)
                || address.hasScheme(ResourceScheme.GIT_HTTP)
                || address.hasScheme(ResourceScheme.GIT_HTTPS);
    }

    @Override
    public ResourceContent resolve(ResourceAddress address, ResourceReferenceScope scope) {
        String path = address.parameters().get("path");
        if (path == null || path.isBlank()) {
            throw new ResourceReferenceResolutionException(
                    "Git resource reference must include a path parameter: " + address.raw());
        }
        GitRepositoryLocation repository = locationResolver.resolve(address, scope);
        String ref = parameter(address, "ref", "branch");
        Optional<byte[]> bytes = contentReader.read(repository, path, ref);
        if (bytes.isEmpty()) {
            throw new ResourceReferenceResolutionException("Git resource does not exist: " + address.raw());
        }
        return new ResourceContent(address.raw(), bytes.get());
    }

    private static String parameter(ResourceAddress address, String first, String second) {
        String value = address.parameters().get(first);
        if (value == null || value.isBlank()) {
            value = address.parameters().get(second);
        }
        return value == null || value.isBlank() ? null : value;
    }
}
