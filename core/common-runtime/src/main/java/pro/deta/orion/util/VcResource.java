package pro.deta.orion.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

public abstract class VcResource {
    @FunctionalInterface
    protected interface VcRead<R> {
        R read();
    }

    private final ReentrantReadWriteLock resourceLock = new ReentrantReadWriteLock();
    private final EventListeners<VcResourceModified> changes = new EventListeners<>();

    public final VcRevision getRevision() {
        return read(() -> Objects.requireNonNull(readRevision(), "revision"));
    }

    protected abstract VcRevision readRevision();

    protected final <R> R read(VcRead<R> operation) {
        resourceLock.readLock().lock();
        try {
            return operation.read();
        } finally {
            resourceLock.readLock().unlock();
        }
    }

    protected final <R> R read(VcRevision expectedRevision, VcRead<R> operation) {
        return read(() -> {
            checkRevision(expectedRevision);
            return operation.read();
        });
    }

    protected abstract VcRevision.Stored commit(VcChangeDescription description);

    public final Subscription subscribe(Consumer<VcResourceModified> subscriber) {
        return changes.subscribe(subscriber);
    }

    private void checkRevision(VcRevision expectedRevision) {
        VcRevision current = getRevision();
        if (!expectedRevision.equals(current)) {
            throw new VcModificationException(expectedRevision, current);
        }
    }

    /**
     * A single caller uses each modification. Changes run only during apply; captured mutable inputs
     * are not snapshots. Operation objects are borrowed during callbacks and must not escape them.
     */
    public class VcModification<M> {
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
            List<VcChange<M>> prepared = pending;
            pending = null;
            VcRevision revision;
            try {
                resourceLock.writeLock().lock();
                try {
                    checkRevision(oldRevision);
                    if (!prepared.isEmpty()) {
                        for (VcChange<M> change : prepared) {
                            change.apply(operations);
                        }
                        commit(description);
                    }
                    revision = getRevision();
                } finally {
                    resourceLock.writeLock().unlock();
                }
            } finally {
                prepared.clear();
            }
            if (!oldRevision.equals(revision)) {
                changes.publish(new VcResourceModified(VcResource.this, oldRevision, revision));
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
