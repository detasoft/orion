package pro.deta.orion.git.sync;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

public abstract class GitSyncStateStore {
    public final GitSyncSnapshot snapshot(
            String repositoryId,
            String remoteAlias) {
        return readSnapshot(new StateKey(repositoryId, remoteAlias));
    }

    public final GitSyncSnapshot recordAttempt(
            String repositoryId,
            String remoteAlias,
            GitSyncState state,
            Instant attemptedAt,
            Optional<GitSyncFailure> failure,
            List<GitSyncConflict> conflicts) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(attemptedAt, "attemptedAt");
        Objects.requireNonNull(failure, "failure");
        List<GitSyncConflict> checkedConflicts = List.copyOf(
                Objects.requireNonNull(conflicts, "conflicts"));
        return updateSnapshot(
                new StateKey(repositoryId, remoteAlias),
                current -> new GitSyncSnapshot(
                        state,
                        Optional.of(attemptedAt),
                        failure,
                        checkedConflicts,
                        current.outboundWork(),
                        current.nextSequence()));
    }

    public final GitOutboundWork enqueue(
            String repositoryId,
            String remoteAlias,
            String refName,
            String desiredObjectId) {
        StateKey key = new StateKey(repositoryId, remoteAlias);
        AtomicReference<GitOutboundWork> enqueued = new AtomicReference<>();
        updateSnapshot(key, current -> {
            Optional<GitOutboundWork> existing = current.work(refName);
            if (existing.isPresent()
                    && existing.get().desiredObjectId().equals(desiredObjectId)) {
                enqueued.set(existing.get());
                return current;
            }
            GitOutboundWork work = GitOutboundWork.pending(
                    refName,
                    desiredObjectId,
                    current.nextSequence());
            List<GitOutboundWork> updated = withoutRef(
                    current.outboundWork(),
                    refName);
            updated.add(work);
            enqueued.set(work);
            return current.copyWithWork(
                    updated,
                    current.nextSequence() + 1);
        });
        return enqueued.get();
    }

    public final Optional<GitOutboundWork> claimNext(
            String repositoryId,
            String remoteAlias,
            Instant now) {
        Objects.requireNonNull(now, "now");
        AtomicReference<GitOutboundWork> claimed = new AtomicReference<>();
        updateSnapshot(new StateKey(repositoryId, remoteAlias), current -> {
            List<GitOutboundWork> updated = new ArrayList<>(
                    current.outboundWork());
            for (int index = 0; index < updated.size(); index++) {
                GitOutboundWork work = updated.get(index);
                if (!work.inFlight() && !work.notBefore().isAfter(now)) {
                    GitOutboundWork inFlight = work.claim();
                    updated.set(index, inFlight);
                    claimed.set(inFlight);
                    return current.copyWithWork(
                            updated,
                            current.nextSequence());
                }
            }
            return current;
        });
        return Optional.ofNullable(claimed.get());
    }

    public final boolean complete(
            String repositoryId,
            String remoteAlias,
            GitOutboundWork completed) {
        Objects.requireNonNull(completed, "completed");
        AtomicBoolean removed = new AtomicBoolean();
        updateSnapshot(new StateKey(repositoryId, remoteAlias), current -> {
            Optional<GitOutboundWork> existing = current.work(
                    completed.refName());
            if (existing.isEmpty() || !existing.get().equals(completed)
                    || !completed.inFlight()) {
                return current;
            }
            removed.set(true);
            return current.copyWithWork(
                    withoutRef(
                            current.outboundWork(),
                            completed.refName()),
                    current.nextSequence());
        });
        return removed.get();
    }

    public final boolean retry(
            String repositoryId,
            String remoteAlias,
            GitOutboundWork failed,
            Instant notBefore) {
        Objects.requireNonNull(failed, "failed");
        Objects.requireNonNull(notBefore, "notBefore");
        AtomicBoolean released = new AtomicBoolean();
        updateSnapshot(new StateKey(repositoryId, remoteAlias), current -> {
            Optional<GitOutboundWork> existing = current.work(failed.refName());
            if (existing.isEmpty() || !existing.get().equals(failed)
                    || !failed.inFlight()) {
                return current;
            }
            List<GitOutboundWork> updated = withoutRef(
                    current.outboundWork(),
                    failed.refName());
            updated.add(failed.pendingAt(notBefore));
            released.set(true);
            return current.copyWithWork(
                    updated,
                    current.nextSequence());
        });
        return released.get();
    }

    protected abstract GitSyncSnapshot readSnapshot(StateKey key);

    protected abstract GitSyncSnapshot updateSnapshot(
            StateKey key,
            UnaryOperator<GitSyncSnapshot> update);

    private static List<GitOutboundWork> withoutRef(
            List<GitOutboundWork> work,
            String refName) {
        List<GitOutboundWork> updated = new ArrayList<>();
        for (GitOutboundWork item : work) {
            if (!item.refName().equals(refName)) {
                updated.add(item);
            }
        }
        return updated;
    }

    protected record StateKey(
            String repositoryId,
            String remoteAlias) {
        protected StateKey {
            repositoryId = requireIdentifier(repositoryId, "repositoryId");
            remoteAlias = requireIdentifier(remoteAlias, "remoteAlias");
        }

        private static String requireIdentifier(String value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }
}
