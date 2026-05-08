package pro.deta.orion.lifecycle.task;

import pro.deta.orion.ApplicationState;

import java.util.List;
import java.util.Objects;

public record LifecycleTaskPlan(ApplicationState phase, List<ExecutionGroup> executionGroups) {
    public LifecycleTaskPlan {
        Objects.requireNonNull(phase, "phase");
        executionGroups = List.copyOf(Objects.requireNonNull(executionGroups, "executionGroups"));
    }

    public String describe() {
        StringBuilder builder = new StringBuilder().append(phase).append(":\n");
        int index = 1;
        for (ExecutionGroup group : executionGroups) {
            for (LifecycleTaskDefinition task : group.tasks()) {
                builder.append("  ").append(index).append(". ").append(task.id());
                if (!task.after().isEmpty()) {
                    builder.append(" after ").append(join(task.after()));
                }
                builder.append('\n');
                index++;
            }
        }
        return builder.toString();
    }

    private static String join(List<LifecycleTaskId> ids) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(ids.get(i));
        }
        return builder.toString();
    }

    public record ExecutionGroup(List<LifecycleTaskDefinition> tasks) {
        public ExecutionGroup {
            tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks"));
        }
    }
}
