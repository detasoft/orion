package pro.deta.orion.git.sync;

import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

public final class InMemoryGitSyncStateStore extends GitSyncStateStore {
    private final Map<StateKey, GitSyncSnapshot> snapshots = new HashMap<>();

    @Override
    protected synchronized GitSyncSnapshot readSnapshot(StateKey key) {
        return snapshots.getOrDefault(key, GitSyncSnapshot.attaching());
    }

    @Override
    protected synchronized GitSyncSnapshot updateSnapshot(
            StateKey key,
            UnaryOperator<GitSyncSnapshot> update) {
        GitSyncSnapshot current = snapshots.getOrDefault(
                key,
                GitSyncSnapshot.attaching());
        GitSyncSnapshot updated = update.apply(current);
        snapshots.put(key, updated);
        return updated;
    }
}
