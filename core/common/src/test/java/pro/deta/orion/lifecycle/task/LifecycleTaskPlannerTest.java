package pro.deta.orion.lifecycle.task;

import org.junit.jupiter.api.Test;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.lifecycle.task.LifecycleRunMode.NON_BLOCKING;
import static pro.deta.orion.lifecycle.task.OrionLifecycleTasks.ACL_LOAD;
import static pro.deta.orion.lifecycle.task.OrionLifecycleTasks.REPOSITORY_STORAGE;
import static pro.deta.orion.lifecycle.task.OrionLifecycleTasks.TRANSPORTS_START;

class LifecycleTaskPlannerTest {
    @Test
    void ordersTasksByDependencies() {
        LifecycleTaskDefinition storage = task(REPOSITORY_STORAGE);
        LifecycleTaskDefinition acl = task(ACL_LOAD, List.of(REPOSITORY_STORAGE), List.of());
        LifecycleTaskDefinition transports = task(TRANSPORTS_START, List.of(ACL_LOAD), List.of());

        LifecycleTaskPlan plan = LifecycleTaskPlanner.plan(
                ApplicationState.STARTING,
                List.of(transports, acl, storage));

        assertThat(taskIds(plan)).containsExactly(REPOSITORY_STORAGE, ACL_LOAD, TRANSPORTS_START);
    }

    @Test
    void duplicateTaskIdInOnePhaseFails() {
        assertThatThrownBy(() -> LifecycleTaskPlanner.plan(
                ApplicationState.STARTING,
                List.of(task(ACL_LOAD), task(ACL_LOAD))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate lifecycle task id")
                .hasMessageContaining("ACL_LOAD");
    }

    @Test
    void dependencyOnUnknownTaskFails() {
        LifecycleTaskId unknown = new LifecycleTaskId("UNKNOWN");

        assertThatThrownBy(() -> LifecycleTaskPlanner.plan(
                ApplicationState.STARTING,
                List.of(task(ACL_LOAD, List.of(unknown), List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN")
                .hasMessageContaining("ACL_LOAD");
    }

    @Test
    void dependencyCycleFails() {
        assertThatThrownBy(() -> LifecycleTaskPlanner.plan(
                ApplicationState.STARTING,
                List.of(
                        task(ACL_LOAD, List.of(TRANSPORTS_START), List.of()),
                        task(TRANSPORTS_START, List.of(ACL_LOAD), List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle")
                .hasMessageContaining("ACL_LOAD");
    }

    @Test
    void beforeDependencyIsEquivalentToTargetAfterCurrentTask() {
        LifecycleTaskDefinition storage = task(REPOSITORY_STORAGE, List.of(), List.of(ACL_LOAD));
        LifecycleTaskDefinition acl = task(ACL_LOAD);

        LifecycleTaskPlan plan = LifecycleTaskPlanner.plan(
                ApplicationState.STARTING,
                List.of(acl, storage));

        assertThat(taskIds(plan)).containsExactly(REPOSITORY_STORAGE, ACL_LOAD);
    }

    @Test
    void planDescriptionIsReadable() {
        LifecycleTaskDefinition storage = task(REPOSITORY_STORAGE);
        LifecycleTaskDefinition acl = task(ACL_LOAD, List.of(REPOSITORY_STORAGE), List.of());

        LifecycleTaskPlan plan = LifecycleTaskPlanner.plan(
                ApplicationState.STARTING,
                List.of(acl, storage));

        assertThat(plan.describe()).contains(
                "STARTING:",
                "1. REPOSITORY_STORAGE",
                "2. ACL_LOAD after REPOSITORY_STORAGE");
    }

    private static LifecycleTaskDefinition task(LifecycleTaskId id) {
        return task(id, List.of(), List.of());
    }

    private static LifecycleTaskDefinition task(
            LifecycleTaskId id,
            List<LifecycleTaskId> after,
            List<LifecycleTaskId> before) {
        return new LifecycleTaskDefinition(
                ApplicationState.STARTING,
                id,
                () -> OrionStageCallResult.EMPTY,
                after,
                before,
                NON_BLOCKING,
                0);
    }

    private static List<LifecycleTaskId> taskIds(LifecycleTaskPlan plan) {
        List<LifecycleTaskId> ids = new ArrayList<>();
        for (LifecycleTaskPlan.ExecutionGroup group : plan.executionGroups()) {
            for (LifecycleTaskDefinition task : group.tasks()) {
                ids.add(task.id());
            }
        }
        return ids;
    }
}
