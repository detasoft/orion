package pro.deta.orion.git.nativestorage.ref;

import pro.deta.orion.git.common.GitObjectId;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class LooseRefStore {
    private static final String NULL_ID = "0".repeat(40);

    private final ConcurrentHashMap<String, String> refs = new ConcurrentHashMap<>();

    public Optional<GitObjectId> read(String refName) {
        Objects.requireNonNull(refName, "refName");
        String value = refs.get(refName);
        return Optional.ofNullable(value).map(GitObjectId::of);
    }

    public Map<String, String> snapshot() {
        return Map.copyOf(new HashMap<>(refs));
    }

    public RefUpdateResult update(String refName, String expectedOldId, String newId) {
        Objects.requireNonNull(refName, "refName");
        Objects.requireNonNull(expectedOldId, "expectedOldId");
        Objects.requireNonNull(newId, "newId");

        if (NULL_ID.equals(expectedOldId)) {
            String existing = refs.putIfAbsent(refName, newId);
            if (existing == null) {
                return RefUpdateResult.CREATED;
            }
            if (existing.equals(newId)) {
                return RefUpdateResult.NO_OP;
            }
            return RefUpdateResult.STALE;
        }

        String current = refs.get(refName);
        if (current == null || !current.equals(expectedOldId)) {
            return RefUpdateResult.STALE;
        }
        if (current.equals(newId)) {
            return RefUpdateResult.NO_OP;
        }
        if (refs.replace(refName, current, newId)) {
            return RefUpdateResult.FAST_FORWARD;
        }
        return RefUpdateResult.STALE;
    }
}
