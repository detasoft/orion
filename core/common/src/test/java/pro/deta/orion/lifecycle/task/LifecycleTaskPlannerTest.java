package pro.deta.orion.lifecycle.task;

import org.junit.jupiter.api.Test;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.lifecycle.task.OrionLifecycleTasks.ACL_LOAD;
import static pro.deta.orion.lifecycle.task.OrionLifecycleTasks.TRANSPORTS_START;

class LifecycleTaskPlannerTest {
    private static final LifecycleTaskId STORAGE_TASK = new LifecycleTaskId("STORAGE");

    @Test
    void ordersTasksByDependencies() {
        LifecycleTaskDefinition storage = task(STORAGE_TASK);
        LifecycleTaskDefinition acl = task(ACL_LOAD, List.of(STORAGE_TASK));
        LifecycleTaskDefinition transports = task(TRANSPORTS_START, List.of(ACL_LOAD));

        LifecycleTaskPlan plan = LifecycleTaskPlanner.plan(
                ApplicationState.STARTING,
                List.of(transports, acl, storage));

        assertThat(taskIds(plan)).containsExactly(STORAGE_TASK, ACL_LOAD, TRANSPORTS_START);
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
                List.of(task(ACL_LOAD, List.of(unknown)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN")
                .hasMessageContaining("ACL_LOAD");
    }

    @Test
    void dependencyCycleFails() {
        assertThatThrownBy(() -> LifecycleTaskPlanner.plan(
                ApplicationState.STARTING,
                List.of(
                        task(ACL_LOAD, List.of(TRANSPORTS_START)),
                        task(TRANSPORTS_START, List.of(ACL_LOAD)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle")
                .hasMessageContaining("ACL_LOAD");
    }

    @Test
    void planDescriptionIsReadable() {
        LifecycleTaskDefinition storage = task(STORAGE_TASK);
        LifecycleTaskDefinition acl = task(ACL_LOAD, List.of(STORAGE_TASK));

        LifecycleTaskPlan plan = LifecycleTaskPlanner.plan(
                ApplicationState.STARTING,
                List.of(acl, storage));

        assertThat(plan.describe()).contains(
                "STARTING:",
                "1. STORAGE",
                "2. ACL_LOAD after STORAGE");
    }

    private static LifecycleTaskDefinition task(LifecycleTaskId id) {
        return task(id, List.of());
    }

    private static LifecycleTaskDefinition task(
            LifecycleTaskId id,
            List<LifecycleTaskId> after) {
        return new LifecycleTaskDefinition(
                ApplicationState.STARTING,
                id,
                "",
                () -> OrionStageCallResult.EMPTY,
                after,
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
