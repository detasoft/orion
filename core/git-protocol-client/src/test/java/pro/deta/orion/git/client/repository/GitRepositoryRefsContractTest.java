package pro.deta.orion.git.client.repository;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.common.GitObjectId;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GitRepositoryRefsContractTest {
    private static final GitObjectId MAIN = id("1111111111111111111111111111111111111111");
    private static final GitObjectId NEXT = id("2222222222222222222222222222222222222222");
    private static final GitObjectId TAG = id("3333333333333333333333333333333333333333");

    @Test
    void listsRefsByPrefixAndResolvesCommitIds() throws Exception {
        InMemoryGitRepositoryRefs refs = new InMemoryGitRepositoryRefs(commit -> true);
        refs.put(ref("refs/heads/main", MAIN));
        refs.put(ref("refs/heads/next", NEXT));
        refs.put(new GitRepositoryRef(
                "refs/tags/v1",
                TAG,
                Optional.of(MAIN),
                Optional.of("refs/heads/main")));

        assertThat(refs.listRefs(new GitRefQuery(List.of("refs/heads/"))))
                .extracting(GitRepositoryRef::name)
                .containsExactly("refs/heads/main", "refs/heads/next");
        assertThat(refs.listRefs(new GitRefQuery(List.of("refs/tags/"))))
                .containsExactly(new GitRepositoryRef(
                        "refs/tags/v1",
                        TAG,
                        Optional.of(MAIN),
                        Optional.of("refs/heads/main")));
        assertThat(refs.resolveCommit("refs/heads/main")).contains(MAIN);
        assertThat(refs.resolveCommit("refs/heads/missing")).isEmpty();
    }

    @Test
    void createsAndCompareAndSetUpdatesRefs() throws Exception {
        InMemoryGitRepositoryRefs refs = new InMemoryGitRepositoryRefs(
                Set.of(MAIN, NEXT)::contains);

        assertThat(refs.updateRef(new GitRefUpdateRequest(
                "refs/heads/main",
                Optional.empty(),
                MAIN))).isEqualTo(GitRefUpdateOutcome.UPDATED);
        assertThat(refs.updateRef(new GitRefUpdateRequest(
                "refs/heads/main",
                Optional.of(MAIN),
                NEXT))).isEqualTo(GitRefUpdateOutcome.UPDATED);
        assertThat(refs.resolveCommit("refs/heads/main")).contains(NEXT);
    }

    @Test
    void staleOrMissingCommitUpdateDoesNotMoveRef() throws Exception {
        InMemoryGitRepositoryRefs refs = new InMemoryGitRepositoryRefs(
                Set.of(MAIN, NEXT)::contains);
        refs.put(ref("refs/heads/main", MAIN));

        assertThat(refs.updateRef(new GitRefUpdateRequest(
                "refs/heads/main",
                Optional.of(TAG),
                NEXT))).isEqualTo(GitRefUpdateOutcome.STALE);
        assertThat(refs.updateRef(new GitRefUpdateRequest(
                "refs/heads/main",
                Optional.of(MAIN),
                TAG))).isEqualTo(GitRefUpdateOutcome.MISSING_COMMIT);
        assertThat(refs.resolveCommit("refs/heads/main")).contains(MAIN);
    }

    private static GitRepositoryRef ref(String name, GitObjectId commitId) {
        return new GitRepositoryRef(name, commitId, Optional.empty(), Optional.empty());
    }

    private static GitObjectId id(String value) {
        return GitObjectId.of(value);
    }
}
