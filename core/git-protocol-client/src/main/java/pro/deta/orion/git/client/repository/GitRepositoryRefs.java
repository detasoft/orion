package pro.deta.orion.git.client.repository;

import pro.deta.orion.git.common.GitObjectId;

import java.util.List;
import java.util.Optional;

/**
 * Backend-neutral ref access independent from pack storage. Implementations
 * list and resolve commit ids and apply {@link GitRefUpdateRequest} atomically
 * with compare-and-set semantics; they must not publish refs to unavailable
 * commits.
 */
public interface GitRepositoryRefs {
    List<GitRepositoryRef> listRefs(GitRefQuery query) throws GitRepositoryAccessException;

    Optional<GitObjectId> resolveCommit(String refName) throws GitRepositoryAccessException;

    GitRefUpdateOutcome updateRef(GitRefUpdateRequest request) throws GitRepositoryAccessException;
}
