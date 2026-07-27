package pro.deta.orion.git.nativestorage.ref;

import pro.deta.orion.git.common.GitObjectId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class LooseRefStore {
    private static final String NULL_ID = "0".repeat(40);

    private final Map<String, String> refs = new HashMap<>();

    public synchronized Optional<GitObjectId> read(String refName) {
        Objects.requireNonNull(refName, "refName");
        String value = refs.get(refName);
        return Optional.ofNullable(value).map(GitObjectId::of);
    }

    public synchronized Map<String, String> snapshot() {
        return Map.copyOf(refs);
    }

    public synchronized RefUpdateResult update(String refName, String expectedOldId, String newId) {
        Objects.requireNonNull(refName, "refName");
        Objects.requireNonNull(expectedOldId, "expectedOldId");
        Objects.requireNonNull(newId, "newId");
        return applyUpdate(refs, refName, expectedOldId, newId);
    }

    public synchronized List<RefUpdateResult> updateAll(List<Update> updates, Runnable beforeUpdates) {
        Objects.requireNonNull(updates, "updates");
        Objects.requireNonNull(beforeUpdates, "beforeUpdates");

        Map<String, String> updatedRefs = new HashMap<>(refs);
        List<RefUpdateResult> results = new ArrayList<>(updates.size());
        boolean anyAccepted = false;
        for (Update update : updates) {
            Objects.requireNonNull(update, "update");
            RefUpdateResult result =
                    applyUpdate(updatedRefs, update.refName(), update.expectedOldId(), update.newId());
            results.add(result);
            if (result != RefUpdateResult.STALE) {
                anyAccepted = true;
            }
        }

        if (anyAccepted) {
            beforeUpdates.run();
            refs.clear();
            refs.putAll(updatedRefs);
        }
        return List.copyOf(results);
    }

    private static RefUpdateResult applyUpdate(
            Map<String, String> targetRefs,
            String refName,
            String expectedOldId,
            String newId) {
        if (NULL_ID.equals(expectedOldId)) {
            String existing = targetRefs.putIfAbsent(refName, newId);
            if (existing == null) {
                return RefUpdateResult.CREATED;
            }
            if (existing.equals(newId)) {
                return RefUpdateResult.NO_OP;
            }
            return RefUpdateResult.STALE;
        }

        String current = targetRefs.get(refName);
        if (current == null || !current.equals(expectedOldId)) {
            return RefUpdateResult.STALE;
        }
        if (current.equals(newId)) {
            return RefUpdateResult.NO_OP;
        }
        targetRefs.put(refName, newId);
        return RefUpdateResult.FAST_FORWARD;
    }

    public record Update(String refName, String expectedOldId, String newId) {
        public Update {
            Objects.requireNonNull(refName, "refName");
            Objects.requireNonNull(expectedOldId, "expectedOldId");
            Objects.requireNonNull(newId, "newId");
        }
    }
}
