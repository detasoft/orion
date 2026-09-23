package pro.deta.orion.git.sync;

import java.util.Map;
import java.util.Objects;

/** Immutable snapshot of branch tips, shared by discovery, fetch and attachment planning. */
public record GitHeads(Map<String, String> heads) {
    public GitHeads {
        heads = Map.copyOf(Objects.requireNonNull(heads, "heads"));
        for (Map.Entry<String, String> entry : heads.entrySet()) {
            GitBranchPlan.requireHead(entry.getKey());
            GitBranchPlan.requireObjectId(entry.getValue(), "head object ID");
        }
    }
}
