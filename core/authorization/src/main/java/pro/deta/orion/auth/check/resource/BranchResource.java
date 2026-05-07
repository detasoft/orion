package pro.deta.orion.auth.check.resource;

import pro.deta.orion.auth.check.NestedResource;

import java.util.Objects;

/**
 * Branch-level resource nested under a repository. Branch grants are evaluated relative to the parent
 * repository; a branch name by itself is not a globally meaningful authorization target.
 */
public record BranchResource(RepositoryResource repository, String branchName)
        implements NestedResource<RepositoryResource> {
    public BranchResource {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(branchName, "branchName");
    }

    public static BranchResource of(RepositoryResource repository, String branchName) {
        return new BranchResource(repository, branchName);
    }

    @Override
    public RepositoryResource parentResource() {
        return repository;
    }

    @Override
    public String describe() {
        return repository.describe() + " branch " + branchName;
    }
}
