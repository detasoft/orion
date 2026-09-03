package pro.deta.orion.git.sync;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record GitAttachPlan(List<GitBranchPlan> branches) {
    public GitAttachPlan {
        List<GitBranchPlan> sorted = new ArrayList<>(
                List.copyOf(Objects.requireNonNull(branches, "branches")));
        sorted.sort(Comparator.comparing(GitBranchPlan::refName));
        Set<String> refs = new HashSet<>();
        for (GitBranchPlan branch : sorted) {
            if (!refs.add(branch.refName())) {
                throw new IllegalArgumentException(
                        "duplicate branch plan: " + branch.refName());
            }
        }
        branches = List.copyOf(sorted);
    }

    public boolean compatible() {
        return conflicts().isEmpty();
    }

    public List<GitBranchPlan> conflicts() {
        List<GitBranchPlan> conflicts = new ArrayList<>();
        for (GitBranchPlan branch : branches) {
            if (branch.action() == GitBranchAction.DIVERGED) {
                conflicts.add(branch);
            }
        }
        return List.copyOf(conflicts);
    }
}
