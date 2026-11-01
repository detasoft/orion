package pro.deta.orion.util;

import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.event.OrionEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;

public abstract class VcResource<R, U extends VcResource.VcUpdateOperations> {
    private final ReentrantReadWriteLock resourceLock = new ReentrantReadWriteLock();
    private final OrionEvents events;
    private volatile VcRevision lastRevision;

    protected VcResource(OrionEventManager eventManager) {
        this(eventManager, new VcRevision.Absent());
    }

    protected VcResource(OrionEventManager eventManager, VcRevision initialRevision) {
        events = eventManager.forTarget(this);
        lastRevision = Objects.requireNonNull(initialRevision, "initialRevision");
    }

    protected abstract R createReadOperation(VcRevision.ReadTarget revision);

    protected abstract U createUpdateOperation();

    public final VcRead withRead(VcRevision.ReadTarget revision) {
        return new VcRead(createReadOperation(Objects.requireNonNull(revision, "revision")));
    }

    public final VcModification withModification() {
        return new VcModification();
    }

    private <T> T inReadLock(Supplier<T> operation) {
        resourceLock.readLock().lock();
        try {
            return operation.get();
        } finally {
            resourceLock.readLock().unlock();
        }
    }

    public final VcRevision getRevision() {
        return lastRevision;
    }

    private void checkRevision(VcRevision expectedRevision) {
        if (!expectedRevision.equals(lastRevision)) {
            throw new VcModificationException(expectedRevision, lastRevision);
        }
    }

    public abstract static class VcUpdateOperations {
        protected abstract VcRevision commit(VcChangeDescription description);

        protected void discard() {}
    }

    /** Operation objects are borrowed under the read-lock and must not escape the callback. */
    public class VcRead {
        private final R operations;

        private VcRead(R operations) {
            this.operations = Objects.requireNonNull(operations, "operations");
        }

        public final <T> T read(Function<R, T> operation) {
            Objects.requireNonNull(operation, "operation");
            return VcResource.this.inReadLock(() -> operation.apply(operations));
        }
    }

    /**
     * A single caller uses each modification. Changes run only during commit; captured mutable inputs
     * are not snapshots. Operation objects are borrowed during callbacks and must not escape them.
     */
    public class VcModification {
        private final VcRevision oldRevision;
        private List<VcChange<U>> pending = new ArrayList<>();

        private VcModification() {
            oldRevision = getRevision();
        }

        public final void addChange(VcChange<U> change) {
            requireOpen();
            pending.add(Objects.requireNonNull(change, "change"));
        }

        public final VcRevision commit(VcChangeDescription description) {
            requireOpen();
            Objects.requireNonNull(description, "description");
            if (resourceLock.getReadHoldCount() > 0 && !resourceLock.isWriteLockedByCurrentThread()) {
                throw new IllegalStateException("Cannot commit a modification from a read operation");
            }
            List<VcChange<U>> prepared = pending;
            pending = null;
            VcRevision revision;
            resourceLock.writeLock().lock();
            Throwable failure = null;
            U operations = null;
            try {
                checkRevision(oldRevision);
                operations = Objects.requireNonNull(createUpdateOperation(), "operations");
                for (VcChange<U> change : prepared) {
                    change.apply(operations);
                }
                revision = Objects.requireNonNull(operations.commit(description), "committed revision");
                lastRevision = revision;
            } catch (RuntimeException | Error error) {
                failure = error;
                throw error;
            } finally {
                try {
                    if (operations != null) operations.discard();
                } catch (RuntimeException | Error cleanup) {
                    if (failure == null) throw cleanup;
                    if (failure != cleanup) failure.addSuppressed(cleanup);
                } finally {
                    resourceLock.writeLock().unlock();
                    prepared.clear();
                }
            }
            if (!oldRevision.equals(revision)) {
                events.publish(new VcResourceModified(VcResource.this, oldRevision, revision));
            }
            return revision;
        }

        public final void discard() {
            if (pending != null) {
                pending.clear();
                pending = null;
            }
        }

        private void requireOpen() {
            if (pending == null) {
                throw new IllegalStateException("Modification is already finished");
            }
        }

    }
}
