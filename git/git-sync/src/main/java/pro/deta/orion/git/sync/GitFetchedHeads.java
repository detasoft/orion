package pro.deta.orion.git.sync;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record GitFetchedHeads(Map<String, String> heads) {
    public GitFetchedHeads {
        Map<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry
                : Objects.requireNonNull(heads, "heads").entrySet()) {
            sorted.put(
                    GitBranchPlan.requireHead(entry.getKey()),
                    Objects.requireNonNull(entry.getValue(), "head object ID"));
        }
        heads = Map.copyOf(sorted);
    }
}
