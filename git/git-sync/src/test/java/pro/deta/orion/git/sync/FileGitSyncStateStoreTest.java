package pro.deta.orion.git.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FileGitSyncStateStoreTest {
    private static final String REPOSITORY = "team/project";
    private static final String UPSTREAM = "upstream";
    private static final String MAIN = "refs/heads/main";
    private static final String A = "1".repeat(40);
    private static final String B = "2".repeat(40);
    private static final String C = "3".repeat(40);

    @Test
    void reloadsStateAttemptsConflictsAndQueuedWork(
            @TempDir Path temporaryDirectory) {
        Instant attemptedAt = Instant.parse("2026-09-04T00:00:00Z");
        GitSyncConflict conflict = new GitSyncConflict(
                MAIN,
                Optional.of(A),
                Optional.of(B),
                Optional.of(C));
        GitSyncFailure failure = new GitSyncFailure(
                GitSyncFailure.Kind.DIVERGENCE,
                false);
        GitSyncStateStore first = new FileGitSyncStateStore(temporaryDirectory);
        first.recordAttempt(
                REPOSITORY,
                UPSTREAM,
                GitSyncState.CONFLICTED,
                attemptedAt,
                Optional.of(failure),
                List.of(conflict));
        first.enqueue(REPOSITORY, UPSTREAM, MAIN, A);

        GitSyncSnapshot reloaded = new FileGitSyncStateStore(temporaryDirectory)
                .snapshot(REPOSITORY, UPSTREAM);

        assertThat(reloaded.state()).isEqualTo(GitSyncState.CONFLICTED);
        assertThat(reloaded.lastAttemptAt()).contains(attemptedAt);
        assertThat(reloaded.lastFailure()).contains(failure);
        assertThat(reloaded.conflicts()).containsExactly(conflict);
        assertThat(reloaded.work(MAIN)).get()
                .extracting(GitOutboundWork::desiredObjectId)
                .isEqualTo(A);
    }

    @Test
    void makesInterruptedInFlightWorkPendingAfterRestart(
            @TempDir Path temporaryDirectory) {
        Instant now = Instant.parse("2026-09-04T00:00:00Z");
        GitSyncStateStore first = new FileGitSyncStateStore(temporaryDirectory);
        first.enqueue(REPOSITORY, UPSTREAM, MAIN, A);
        GitOutboundWork interrupted = first.claimNext(REPOSITORY, UPSTREAM, now)
                .orElseThrow();
        assertThat(interrupted.inFlight()).isTrue();

        GitSyncStateStore restarted = new FileGitSyncStateStore(temporaryDirectory);
        GitOutboundWork pending = restarted.snapshot(REPOSITORY, UPSTREAM)
                .work(MAIN)
                .orElseThrow();

        assertThat(pending.inFlight()).isFalse();
        assertThat(pending.attempt()).isEqualTo(1);
        assertThat(restarted.claimNext(REPOSITORY, UPSTREAM, now))
                .get()
                .extracting(GitOutboundWork::sequence)
                .isEqualTo(interrupted.sequence());
    }

    @Test
    void serializesOnlySafeOperationalFacts(
            @TempDir Path temporaryDirectory) throws Exception {
        String secretRepository = "https://user:token@example.invalid/project.git";
        String secretRemote = "Authorization: Basic c2VjcmV0";
        GitSyncStateStore store = new FileGitSyncStateStore(temporaryDirectory);

        store.recordAttempt(
                secretRepository,
                secretRemote,
                GitSyncState.OFFLINE,
                Instant.parse("2026-09-04T00:00:00Z"),
                Optional.of(new GitSyncFailure(
                        GitSyncFailure.Kind.TRANSPORT,
                        true)),
                List.of());
        store.enqueue(secretRepository, secretRemote, MAIN, B);

        List<Path> files;
        try (var entries = Files.list(temporaryDirectory)) {
            files = entries.toList();
        }
        assertThat(files).hasSize(1);
        String fileName = files.getFirst().getFileName().toString();
        String json = Files.readString(files.getFirst());
        assertThat(fileName)
                .doesNotContain("token", "Authorization", "secret", "user");
        assertThat(json)
                .doesNotContain(
                        secretRepository,
                        secretRemote,
                        "token",
                        "Authorization",
                        "Basic",
                        "packBytes");
    }

    @Test
    void forcesTheStateDirectoryAfterAtomicReplacement(
            @TempDir Path temporaryDirectory) {
        AtomicReference<Path> forcedDirectory = new AtomicReference<>();
        GitSyncStateStore store = new FileGitSyncStateStore(
                temporaryDirectory,
                forcedDirectory::set);

        store.enqueue(REPOSITORY, UPSTREAM, MAIN, A);

        assertThat(forcedDirectory).hasValue(
                temporaryDirectory.toAbsolutePath().normalize());
    }
}
