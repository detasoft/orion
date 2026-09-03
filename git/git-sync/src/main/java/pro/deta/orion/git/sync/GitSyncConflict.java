package pro.deta.orion.git.sync;

import java.util.Objects;
import java.util.Optional;

public record GitSyncConflict(
        String refName,
        Optional<String> localObjectId,
        Optional<String> upstreamObjectId,
        Optional<String> mergeBase) {
    public GitSyncConflict {
        GitBranchPlan.requireHead(refName);
        localObjectId = validatedObjectId(localObjectId, "localObjectId");
        upstreamObjectId = validatedObjectId(upstreamObjectId, "upstreamObjectId");
        mergeBase = validatedObjectId(mergeBase, "mergeBase");
    }

    static GitSyncConflict from(GitBranchPlan branch) {
        return new GitSyncConflict(
                branch.refName(),
                branch.localObjectId(),
                branch.upstreamObjectId(),
                branch.mergeBase());
    }

    private static Optional<String> validatedObjectId(
            Optional<String> value,
            String name) {
        Optional<String> checked = Objects.requireNonNull(value, name);
        return checked.map(item -> GitBranchPlan.requireObjectId(item, name));
    }
}
