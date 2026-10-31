package pro.deta.orion.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public abstract class Modification<T extends VcResource> {
    private final T target;
    private final VcRevision oldRevision;
    private final VcChangeDescription vcChangeDescription;
    private final List<Consumer<T>> changes = new ArrayList<>();

    protected Modification(T target, VcRevision oldRevision, VcChangeDescription vcChangeDescription) {
        this.target = target;
        this.oldRevision = oldRevision;
        this.vcChangeDescription = vcChangeDescription;
    }

    void addChange(Consumer<T> change) {
        changes.add(change);
    }

    public void apply() throws IOException {
        checkRevision();
        synchronized (target) {
            checkRevision();
            target.commit(vcChangeDescription, changes);
        }
    }

    private void checkRevision() {
        if (!target.getRevision().equals(oldRevision))
            throw new IllegalStateException("Concurrent modification detected.");
    }

    public void discard() throws IOException {};
}
