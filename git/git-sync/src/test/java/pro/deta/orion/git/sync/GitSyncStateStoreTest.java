package pro.deta.orion.git.sync;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitSyncStateStoreTest {
    private static final String REPOSITORY = "team/project";
    private static final String UPSTREAM = "upstream";
    private static final String MAIN = "refs/heads/main";
    private static final String RELEASE = "refs/heads/release";
    private static final String A = "1".repeat(40);
    private static final String B = "2".repeat(40);
    private static final String C = "3".repeat(40);

    private final GitSyncStateStore store = new InMemoryGitSyncStateStore();

    @Test
    void startsAttachingAndKeepsMirrorKeysIndependent() {
        assertThat(store.snapshot(REPOSITORY, UPSTREAM))
                .isEqualTo(GitSyncSnapshot.attaching());

        store.enqueue(REPOSITORY, UPSTREAM, MAIN, A);

        assertThat(store.snapshot(REPOSITORY, UPSTREAM).outboundWork())
                .extracting(GitOutboundWork::desiredObjectId)
                .containsExactly(A);
        assertThat(store.snapshot("other/project", UPSTREAM).outboundWork())
                .isEmpty();
    }

    @Test
    void coalescesToTheLatestTipAndCannotCompleteNewerWork() {
        Instant now = Instant.parse("2026-09-04T00:00:00Z");
        store.enqueue(REPOSITORY, UPSTREAM, MAIN, A);
        GitOutboundWork first = store.claimNext(REPOSITORY, UPSTREAM, now)
                .orElseThrow();

        store.enqueue(REPOSITORY, UPSTREAM, MAIN, B);

        assertThat(store.complete(REPOSITORY, UPSTREAM, first)).isFalse();
        GitOutboundWork latest = store.snapshot(REPOSITORY, UPSTREAM)
                .work(MAIN)
                .orElseThrow();
        assertThat(latest.desiredObjectId()).isEqualTo(B);
        assertThat(latest.sequence()).isGreaterThan(first.sequence());
        assertThat(latest.inFlight()).isFalse();
        assertThat(latest.attempt()).isZero();

        GitOutboundWork claimedLatest = store.claimNext(REPOSITORY, UPSTREAM, now)
                .orElseThrow();
        assertThat(store.complete(REPOSITORY, UPSTREAM, claimedLatest)).isTrue();
        assertThat(store.snapshot(REPOSITORY, UPSTREAM).outboundWork()).isEmpty();
    }

    @Test
    void retainsConflictAndLastAttemptWhileQueueWorkChanges() {
        Instant attemptedAt = Instant.parse("2026-09-04T00:01:00Z");
        GitSyncConflict conflict = new GitSyncConflict(
                MAIN,
                Optional.of(A),
                Optional.of(B),
                Optional.of(C));
        GitSyncFailure failure = new GitSyncFailure(
                GitSyncFailure.Kind.DIVERGENCE,
                false);

        store.recordAttempt(
                REPOSITORY,
                UPSTREAM,
                GitSyncState.CONFLICTED,
                attemptedAt,
                Optional.of(failure),
                List.of(conflict));
        store.enqueue(REPOSITORY, UPSTREAM, RELEASE, C);
        GitOutboundWork claimed = store.claimNext(
                REPOSITORY,
                UPSTREAM,
                attemptedAt).orElseThrow();
        assertThat(store.retry(
                REPOSITORY,
                UPSTREAM,
                claimed,
                attemptedAt.plusSeconds(30))).isTrue();

        GitSyncSnapshot snapshot = store.snapshot(REPOSITORY, UPSTREAM);
        assertThat(snapshot.state()).isEqualTo(GitSyncState.CONFLICTED);
        assertThat(snapshot.lastAttemptAt()).contains(attemptedAt);
        assertThat(snapshot.lastFailure()).contains(failure);
        assertThat(snapshot.conflicts()).containsExactly(conflict);
        assertThat(snapshot.work(RELEASE)).get()
                .extracting(GitOutboundWork::notBefore)
                .isEqualTo(attemptedAt.plusSeconds(30));
    }

    @Test
    void rejectsMalformedObjectIdsBeforeTheyReachDurableState() {
        assertThatThrownBy(() -> store.enqueue(
                REPOSITORY,
                UPSTREAM,
                MAIN,
                "not-an-object-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("40 lowercase hexadecimal digits");

        assertThatThrownBy(() -> new GitSyncConflict(
                MAIN,
                Optional.of("g".repeat(40)),
                Optional.of(A),
                Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("40 lowercase hexadecimal digits");
    }
}
