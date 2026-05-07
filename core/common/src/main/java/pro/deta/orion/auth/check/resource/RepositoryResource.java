package pro.deta.orion.auth.check.resource;

import pro.deta.orion.auth.check.RootResource;

import java.util.Objects;

/**
 * Repository-level resource used for create, read and write checks before the git service opens or creates storage.
 */
public record RepositoryResource(String repositoryName) implements RootResource {
    public RepositoryResource {
        Objects.requireNonNull(repositoryName, "repositoryName");
    }

    public static RepositoryResource of(String repositoryName) {
        return new RepositoryResource(repositoryName);
    }

    @Override
    public String describe() {
        return "repository " + repositoryName;
    }
}
