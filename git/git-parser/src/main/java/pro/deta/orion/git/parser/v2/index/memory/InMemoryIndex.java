package pro.deta.orion.git.parser.v2.index.memory;

import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.index.GitIndexApi;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static pro.deta.orion.git.parser.v2.data.RefUpdateResult.Status.*;

/** Transient repository refs with atomic snapshots and compare-and-set updates. */
public final class InMemoryIndex implements GitIndexApi {
    private final Map<RefId, ObjectId> refs = new LinkedHashMap<>();
    private Head head = new Head.Symbolic(new RefId("refs/heads/main"));
    private boolean closed;

    public synchronized RefsSnapshot snapshotRefs() throws IOException {
        requireOpen();
        return new RefsSnapshot(refs, head);
    }

    public void updateHead(Head value) throws IOException {
        Objects.requireNonNull(value, "head");
        if (value instanceof Head.Symbolic symbolic) {
            symbolic.target().requireFullName();
        }
        synchronized (this) {
            requireOpen();
            head = value;
        }
    }

    public List<RefUpdateResult> updateRefs(List<RefUpdate> updates, boolean atomic) {
        updates = List.copyOf(updates);
        Set<RefId> names = new HashSet<>();
        for (RefUpdate update : updates) {
            update.ref().requireFullName();
            if (!names.add(update.ref())) {
                throw new IllegalArgumentException("Duplicate ref update: " + update.ref());
            }
        }
        try {
            List<RefUpdateResult> results = new ArrayList<>(updates.size());
            synchronized (this) {
                requireOpen();
                boolean failed = false;
                for (RefUpdate update : updates) {
                    RefUpdateResult.Status status = Objects.equals(refs.get(update.ref()),
                            update.expectedOld().orElse(null)) ? APPLIED : EXPECTED_OLD_MISMATCH;
                    results.add(new RefUpdateResult(update, status, Optional.empty()));
                    failed |= status != APPLIED;
                }
                for (int index = 0; index < results.size(); index++) {
                    RefUpdateResult result = results.get(index);
                    if (result.status() != APPLIED) {
                        continue;
                    }
                    RefUpdate update = result.update();
                    if (atomic && failed) {
                        results.set(index, new RefUpdateResult(update, ATOMIC_ABORTED, Optional.empty()));
                    } else if (update.newId().isPresent()) {
                        refs.put(update.ref(), update.newId().orElseThrow());
                    } else {
                        refs.remove(update.ref());
                    }
                }
            }
            return List.copyOf(results);
        } catch (IOException error) {
            List<RefUpdateResult> results = new ArrayList<>(updates.size());
            for (RefUpdate update : updates) {
                results.add(new RefUpdateResult(update, STORAGE_ERROR, Optional.ofNullable(error.getMessage())));
            }
            return List.copyOf(results);
        }
    }

    private void requireOpen() throws ClosedChannelException {
        if (closed) {
            throw new ClosedChannelException();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        refs.clear();
    }
}
