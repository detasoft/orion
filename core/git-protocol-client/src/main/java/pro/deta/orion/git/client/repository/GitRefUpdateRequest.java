package pro.deta.orion.git.client.repository;

import pro.deta.orion.git.common.GitObjectId;

import java.util.Objects;
import java.util.Optional;

public record GitRefUpdateRequest(
        String refName,
        Optional<GitObjectId> expectedOldCommitId,
        GitObjectId newCommitId) {
    public GitRefUpdateRequest {
        if (Objects.requireNonNull(refName, "refName").isBlank()) {
            throw new IllegalArgumentException("refName must not be blank");
        }
        expectedOldCommitId = Objects.requireNonNull(expectedOldCommitId, "expectedOldCommitId");
        Objects.requireNonNull(newCommitId, "newCommitId");
    }
}
