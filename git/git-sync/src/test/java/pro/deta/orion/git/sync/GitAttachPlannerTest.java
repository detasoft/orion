package pro.deta.orion.git.sync;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitAttachPlannerTest {
    private static final String A = "1".repeat(40);
    private static final String B1 = "2".repeat(40);
    private static final String B2 = "3".repeat(40);
    private static final String C = "4".repeat(40);
    private static final String D1 = "5".repeat(40);
    private static final String D2 = "6".repeat(40);
    private static final String E0 = "7".repeat(40);
    private static final String E1 = "8".repeat(40);
    private static final String E2 = "9".repeat(40);

    private final FakeRelationships relationships = new FakeRelationships();
    private final GitAttachPlanner planner = new GitAttachPlanner();

    @Test
    void classifiesTheCompleteBranchUnion() {
        relationships.ancestor(B1, B2);
        relationships.ancestor(D1, D2);
        relationships.mergeBase(E1, E2, E0);

        GitAttachPlan plan = planner.plan(
                Map.of(
                        head("fast-forward"), B1,
                        head("no-op"), C,
                        head("push"), D2,
                        head("diverged"), E1),
                Map.of(
                        head("create"), A,
                        head("fast-forward"), B2,
                        head("no-op"), C,
                        head("push"), D1,
                        head("diverged"), E2),
                relationships);

        assertThat(plan.branches())
                .extracting(GitBranchPlan::refName)
                .containsExactly(
                        head("create"),
                        head("diverged"),
                        head("fast-forward"),
                        head("no-op"),
                        head("push"));
        assertThat(plan.branches())
                .extracting(GitBranchPlan::action)
                .containsExactly(
                        GitBranchAction.CREATE_LOCAL,
                        GitBranchAction.DIVERGED,
                        GitBranchAction.FAST_FORWARD_LOCAL,
                        GitBranchAction.NO_OP,
                        GitBranchAction.PUSH_UPSTREAM);
        assertThat(plan.compatible()).isFalse();
        assertThat(plan.conflicts()).singleElement()
                .extracting(GitBranchPlan::mergeBase)
                .isEqualTo(Optional.of(E0));
    }

    @Test
    void reportsEveryDivergenceInStableOrder() {
        relationships.mergeBase(A, B1, E0);
        relationships.mergeBase(C, D1, Optional.empty());

        GitAttachPlan plan = planner.plan(
                Map.of(head("zulu"), C, head("alpha"), A),
                Map.of(head("zulu"), D1, head("alpha"), B1),
                relationships);

        assertThat(plan.conflicts())
                .extracting(GitBranchPlan::refName)
                .containsExactly(head("alpha"), head("zulu"));
        assertThat(plan.conflicts())
                .extracting(GitBranchPlan::mergeBase)
                .containsExactly(Optional.of(E0), Optional.empty());
    }

    @Test
    void rejectsRefsOutsideTheAllBranchContract() {
        assertThatThrownBy(() -> planner.plan(
                Map.of("refs/tags/v1", A),
                Map.of(),
                relationships))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refs/heads/");
    }

    @Test
    void rejectsMalformedObjectIdsAtThePlanningBoundary() {
        assertThatThrownBy(() -> planner.plan(
                Map.of(head("main"), "not-an-object-id"),
                Map.of(),
                relationships))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("40 lowercase hexadecimal digits");

        assertThatThrownBy(() -> new GitBranchPlan(
                head("main"),
                Optional.of("g".repeat(40)),
                Optional.empty(),
                GitBranchAction.PUSH_UPSTREAM,
                Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("40 lowercase hexadecimal digits");
    }

    private static String head(String branch) {
        return "refs/heads/" + branch;
    }

    private static final class FakeRelationships implements GitCommitRelationships {
        private final Map<Pair, Boolean> ancestors = new HashMap<>();
        private final Map<Pair, Optional<String>> mergeBases = new HashMap<>();

        void ancestor(String ancestor, String descendant) {
            ancestors.put(new Pair(ancestor, descendant), true);
        }

        void mergeBase(String first, String second, String mergeBase) {
            mergeBase(first, second, Optional.of(mergeBase));
        }

        void mergeBase(String first, String second, Optional<String> mergeBase) {
            mergeBases.put(new Pair(first, second), mergeBase);
            mergeBases.put(new Pair(second, first), mergeBase);
        }

        @Override
        public boolean isAncestor(String ancestor, String descendant) {
            return ancestor.equals(descendant)
                    || ancestors.getOrDefault(new Pair(ancestor, descendant), false);
        }

        @Override
        public Optional<String> mergeBase(String first, String second) {
            return mergeBases.getOrDefault(new Pair(first, second), Optional.empty());
        }
    }

    private record Pair(String first, String second) {
    }
}
