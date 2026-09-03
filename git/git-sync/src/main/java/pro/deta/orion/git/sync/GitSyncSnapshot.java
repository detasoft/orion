package pro.deta.orion.git.sync;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record GitSyncSnapshot(
        GitSyncState state,
        Optional<Instant> lastAttemptAt,
        Optional<GitSyncFailure> lastFailure,
        List<GitSyncConflict> conflicts,
        List<GitOutboundWork> outboundWork,
        long nextSequence) {
    public GitSyncSnapshot {
        Objects.requireNonNull(state, "state");
        lastAttemptAt = Objects.requireNonNull(
                lastAttemptAt,
                "lastAttemptAt");
        lastFailure = Objects.requireNonNull(lastFailure, "lastFailure");
        conflicts = List.copyOf(Objects.requireNonNull(conflicts, "conflicts"));
        List<GitOutboundWork> sortedWork = new ArrayList<>(
                List.copyOf(Objects.requireNonNull(
                        outboundWork,
                        "outboundWork")));
        sortedWork.sort(Comparator.comparing(GitOutboundWork::refName));
        Set<String> refs = new HashSet<>();
        long highestSequence = 0;
        for (GitOutboundWork work : sortedWork) {
            if (!refs.add(work.refName())) {
                throw new IllegalArgumentException(
                        "duplicate outbound ref: " + work.refName());
            }
            highestSequence = Math.max(highestSequence, work.sequence());
        }
        if (nextSequence < 1 || nextSequence <= highestSequence) {
            throw new IllegalArgumentException(
                    "nextSequence must follow all outbound work");
        }
        outboundWork = List.copyOf(sortedWork);
    }

    public static GitSyncSnapshot attaching() {
        return new GitSyncSnapshot(
                GitSyncState.ATTACHING,
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(),
                1);
    }

    public Optional<GitOutboundWork> work(String refName) {
        for (GitOutboundWork work : outboundWork) {
            if (work.refName().equals(refName)) {
                return Optional.of(work);
            }
        }
        return Optional.empty();
    }

    GitSyncSnapshot pendingAfterRestart() {
        List<GitOutboundWork> pending = new ArrayList<>();
        boolean changed = false;
        for (GitOutboundWork work : outboundWork) {
            if (work.inFlight()) {
                pending.add(work.pendingAt(work.notBefore()));
                changed = true;
            } else {
                pending.add(work);
            }
        }
        if (!changed) {
            return this;
        }
        return copyWithWork(pending, nextSequence);
    }

    GitSyncSnapshot copyWithWork(
            List<GitOutboundWork> work,
            long newNextSequence) {
        return new GitSyncSnapshot(
                state,
                lastAttemptAt,
                lastFailure,
                conflicts,
                work,
                newNextSequence);
    }
}
