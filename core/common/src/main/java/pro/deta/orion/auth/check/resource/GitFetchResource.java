package pro.deta.orion.auth.check.resource;

import pro.deta.orion.auth.check.NestedResource;
import pro.deta.orion.git.common.GitFetchAccessRequest;

import java.util.Objects;

/**
 * Fetch-specific resource: it protects a concrete set of wanted git objects inside one repository.
 * Its parent is the repository because fetch rules are only meaningful after repository access is allowed.
 */
public record GitFetchResource(RepositoryResource repository, GitFetchAccessRequest request)
        implements NestedResource<RepositoryResource> {
    public GitFetchResource {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(request, "request");
        if (!Objects.equals(repository.repositoryName(), request.repositoryName())) {
            throw new IllegalArgumentException("Fetch repository does not match parent repository");
        }
    }

    public static GitFetchResource of(GitFetchAccessRequest request) {
        return new GitFetchResource(RepositoryResource.of(request.repositoryName()), request);
    }

    @Override
    public RepositoryResource parentResource() {
        return repository;
    }

    @Override
    public String describe() {
        return "git fetch from repository " + request.repositoryName();
    }
}
