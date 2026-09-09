package pro.deta.orion.auth.check.resource;

import pro.deta.orion.auth.check.RootResource;
import pro.deta.orion.schema.orion.RepositoryName;

/**
 * Repository-level resource used for create, read and write checks before the git service opens or creates storage.
 */
public record RepositoryResource(String repositoryName) implements RootResource {
    public RepositoryResource {
        repositoryName = RepositoryName.parse(repositoryName).value();
    }

    public static RepositoryResource of(String repositoryName) {
        return new RepositoryResource(repositoryName);
    }

    @Override
    public String describe() {
        return "repository " + repositoryName;
    }
}
