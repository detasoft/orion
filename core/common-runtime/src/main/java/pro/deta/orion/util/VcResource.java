package pro.deta.orion.util;

import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.event.OrionEvents;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Open one operation to read several fields from the same version. For a single result, concrete resources
 * expose shortcuts backed by read, which opens and closes the operation for the caller.
 */
public abstract class VcResource<O extends VcResource.VcOperation> {
    private final ReentrantReadWriteLock resourceLock = new ReentrantReadWriteLock();
    protected final OrionEvents events;

    protected VcResource(OrionEventManager eventManager) {
        events = eventManager.forTarget(this);
    }

    public final O open(VcRevision revision) {
        Objects.requireNonNull(revision, "revision");
        resourceLock.readLock().lock();
        try {
            return Objects.requireNonNull(createOperation(revision), "operation");
        } finally {
            resourceLock.readLock().unlock();
        }
    }

    protected abstract O createOperation(VcRevision revision);

    public final <T, E extends Exception> T read(VcRevision revision,
            CheckedFunction<? super O, ? extends T, E> action) throws E {
        Objects.requireNonNull(action, "action");
        try (O operation = open(revision)) {
            return action.apply(operation);
        }
    }

    protected final void publishChange(VcRevision oldRevision, VcRevision revision) {
        Objects.requireNonNull(oldRevision, "oldRevision");
        Objects.requireNonNull(revision, "revision");
        if (!oldRevision.equals(revision)) {
            events.publish(new VcResourceModified(this, oldRevision, revision));
        }
    }

    public abstract class VcOperation implements AutoCloseable {
        private final VcRevision.Stored revision;

        protected VcOperation(VcRevision.Stored revision) {
            this.revision = Objects.requireNonNull(revision, "revision");
        }

        public final VcRevision.Stored revision() {
            return revision;
        }

        public final VcRevision.Stored commit(VcChangeDescription description) {
            Objects.requireNonNull(description, "description");
            if (resourceLock.getReadHoldCount() > 0 && !resourceLock.isWriteLockedByCurrentThread()) {
                throw new IllegalStateException("Cannot commit while opening an operation");
            }
            resourceLock.writeLock().lock();
            try {
                return Objects.requireNonNull(commitChanges(description), "committed revision");
            } finally {
                resourceLock.writeLock().unlock();
            }
        }

        protected abstract VcRevision.Stored commitChanges(VcChangeDescription description);

        @Override
        public abstract void close();
    }
}
