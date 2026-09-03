package pro.deta.orion.git.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class GitAttachPlanner {
    public GitAttachPlan plan(
            Map<String, String> localHeads,
            Map<String, String> upstreamHeads,
            GitCommitRelationships relationships) {
        Map<String, String> local = validatedHeads(localHeads, "localHeads");
        Map<String, String> upstream = validatedHeads(upstreamHeads, "upstreamHeads");
        Objects.requireNonNull(relationships, "relationships");
        Set<String> refNames = new TreeSet<>(local.keySet());
        refNames.addAll(upstream.keySet());
        List<GitBranchPlan> branches = new ArrayList<>();
        for (String refName : refNames) {
            branches.add(classify(
                    refName,
                    local.get(refName),
                    upstream.get(refName),
                    relationships));
        }
        return new GitAttachPlan(branches);
    }

    private static GitBranchPlan classify(
            String refName,
            String local,
            String upstream,
            GitCommitRelationships relationships) {
        GitBranchAction action;
        Optional<String> mergeBase = Optional.empty();
        if (local == null) {
            action = GitBranchAction.CREATE_LOCAL;
        } else if (upstream == null) {
            action = GitBranchAction.PUSH_UPSTREAM;
        } else if (local.equals(upstream)) {
            action = GitBranchAction.NO_OP;
        } else if (relationships.isAncestor(local, upstream)) {
            action = GitBranchAction.FAST_FORWARD_LOCAL;
        } else if (relationships.isAncestor(upstream, local)) {
            action = GitBranchAction.PUSH_UPSTREAM;
        } else {
            action = GitBranchAction.DIVERGED;
            mergeBase = relationships.mergeBase(local, upstream);
        }
        return new GitBranchPlan(
                refName,
                Optional.ofNullable(local),
                Optional.ofNullable(upstream),
                action,
                mergeBase);
    }

    private static Map<String, String> validatedHeads(
            Map<String, String> heads,
            String name) {
        Map<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry
                : Objects.requireNonNull(heads, name).entrySet()) {
            String refName = GitBranchPlan.requireHead(entry.getKey());
            String objectId = GitBranchPlan.requireObjectId(
                    entry.getValue(),
                    name + " value");
            sorted.put(refName, objectId);
        }
        return Map.copyOf(sorted);
    }
}
