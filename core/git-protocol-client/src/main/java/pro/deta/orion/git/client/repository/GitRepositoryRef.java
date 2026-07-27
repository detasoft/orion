package pro.deta.orion.git.client.repository;

import pro.deta.orion.git.common.GitObjectId;

import java.util.Objects;
import java.util.Optional;

public record GitRepositoryRef(
        String name,
        GitObjectId commitId,
        Optional<GitObjectId> peeledCommitId,
        Optional<String> symrefTarget) {
    public GitRepositoryRef {
        if (Objects.requireNonNull(name, "name").isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(commitId, "commitId");
        peeledCommitId = Objects.requireNonNull(peeledCommitId, "peeledCommitId");
        symrefTarget = Objects.requireNonNull(symrefTarget, "symrefTarget");
    }
}
