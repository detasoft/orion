package pro.deta.orion.git.client.repository;

import pro.deta.orion.git.common.GitObjectId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

final class InMemoryGitRepositoryRefs implements GitRepositoryRefs {
    private final Map<String, GitRepositoryRef> refs = new LinkedHashMap<>();
    private final Predicate<GitObjectId> commitExists;

    InMemoryGitRepositoryRefs(Predicate<GitObjectId> commitExists) {
        this.commitExists = commitExists;
    }

    void put(GitRepositoryRef ref) {
        refs.put(ref.name(), ref);
    }

    @Override
    public synchronized List<GitRepositoryRef> listRefs(GitRefQuery query) {
        List<GitRepositoryRef> result = new ArrayList<>();
        for (GitRepositoryRef ref : refs.values()) {
            if (query.matches(ref.name())) {
                result.add(ref);
            }
        }
        result.sort(Comparator.comparing(GitRepositoryRef::name));
        return List.copyOf(result);
    }

    @Override
    public synchronized Optional<GitObjectId> resolveCommit(String refName) {
        GitRepositoryRef ref = refs.get(refName);
        return ref == null ? Optional.empty() : Optional.of(ref.commitId());
    }

    @Override
    public synchronized GitRefUpdateOutcome updateRef(GitRefUpdateRequest request) {
        if (!commitExists.test(request.newCommitId())) {
            return GitRefUpdateOutcome.MISSING_COMMIT;
        }

        GitRepositoryRef current = refs.get(request.refName());
        if (!matchesExpected(current, request.expectedOldCommitId())) {
            return GitRefUpdateOutcome.STALE;
        }

        refs.put(request.refName(), new GitRepositoryRef(
                request.refName(),
                request.newCommitId(),
                Optional.empty(),
                Optional.empty()));
        return GitRefUpdateOutcome.UPDATED;
    }

    private static boolean matchesExpected(
            GitRepositoryRef current,
            Optional<GitObjectId> expectedOldCommitId) {
        if (expectedOldCommitId.isEmpty()) {
            return current == null;
        }
        return current != null && current.commitId().equals(expectedOldCommitId.orElseThrow());
    }
}
