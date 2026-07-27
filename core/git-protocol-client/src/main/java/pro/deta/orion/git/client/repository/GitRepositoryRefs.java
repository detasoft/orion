package pro.deta.orion.git.client.repository;

import pro.deta.orion.git.common.GitObjectId;

import java.util.List;
import java.util.Optional;

public interface GitRepositoryRefs {
    List<GitRepositoryRef> listRefs(GitRefQuery query) throws GitRepositoryAccessException;

    Optional<GitObjectId> resolveCommit(String refName) throws GitRepositoryAccessException;

    GitRefUpdateOutcome updateRef(GitRefUpdateRequest request) throws GitRepositoryAccessException;
}
