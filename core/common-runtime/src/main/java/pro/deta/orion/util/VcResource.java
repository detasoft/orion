package pro.deta.orion.util;

import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.event.OrionEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;

public abstract class VcResource {
    private final ReentrantReadWriteLock resourceLock = new ReentrantReadWriteLock();
    private final OrionEvents events;
    private volatile VcRevision lastRevision = new VcRevision.Absent();

    protected VcResource(OrionEventManager eventManager) {
        events = eventManager.forTarget(this);
    }

    private <R> R inReadLock(Supplier<R> operation) {
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
    public class VcRead<M> {
        private final M operations;

        public VcRead(M operations) {
            this.operations = Objects.requireNonNull(operations, "operations");
        }

        public final <R> R read(Function<M, R> operation) {
            Objects.requireNonNull(operation, "operation");
            return VcResource.this.inReadLock(() -> operation.apply(operations));
        }
    }

    /**
     * A single caller uses each modification. Changes run only during apply; captured mutable inputs
     * are not snapshots. Operation objects are borrowed during callbacks and must not escape them.
     */
    public class VcModification<M extends VcUpdateOperations> {
        private final VcRevision oldRevision;
        private final VcChangeDescription description;
        private final M operations;
        private List<VcChange<M>> pending = new ArrayList<>();

        public VcModification(VcChangeDescription description, M operations) {
            this.description = Objects.requireNonNull(description, "description");
            oldRevision = getRevision();
            this.operations = Objects.requireNonNull(operations, "operations");
        }

        public final void addChange(VcChange<M> change) {
            requireOpen();
            pending.add(Objects.requireNonNull(change, "change"));
        }

        public final VcRevision apply() {
            requireOpen();
            if (resourceLock.getReadHoldCount() > 0 && !resourceLock.isWriteLockedByCurrentThread()) {
                throw new IllegalStateException("Cannot apply a modification from a read operation");
            }
            List<VcChange<M>> prepared = pending;
            pending = null;
            VcRevision revision;
            resourceLock.writeLock().lock();
            Throwable failure = null;
            try {
                checkRevision(oldRevision);
                for (VcChange<M> change : prepared) {
                    change.apply(operations);
                }
                revision = Objects.requireNonNull(operations.commit(description), "committed revision");
                lastRevision = revision;
            } catch (RuntimeException | Error error) {
                failure = error;
                throw error;
            } finally {
                try {
                    operations.discard();
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
                operations.discard();
            }
        }

        private void requireOpen() {
            if (pending == null) {
                throw new IllegalStateException("Modification is already finished");
            }
        }

    }
}
