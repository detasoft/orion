package pro.deta.orion.lifecycle.task;

import pro.deta.orion.ApplicationState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class LifecycleTaskPlanner {
    private LifecycleTaskPlanner() {
    }

    public static LifecycleTaskPlan plan(ApplicationState phase, List<LifecycleTaskDefinition> definitions) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(definitions, "definitions");

        Map<LifecycleTaskId, LifecycleTaskDefinition> definitionsById = definitionsForPhase(phase, definitions);
        Map<LifecycleTaskId, LinkedHashSet<LifecycleTaskId>> dependencies = dependencies(definitionsById);
        Map<LifecycleTaskId, LifecycleTaskDefinition> normalizedDefinitions =
                normalizedDefinitions(definitionsById, dependencies);
        return new LifecycleTaskPlan(phase, executionGroups(normalizedDefinitions, dependencies));
    }

    private static Map<LifecycleTaskId, LifecycleTaskDefinition> definitionsForPhase(
            ApplicationState phase,
            List<LifecycleTaskDefinition> definitions) {
        Map<LifecycleTaskId, LifecycleTaskDefinition> definitionsById = new LinkedHashMap<>();
        for (LifecycleTaskDefinition definition : definitions) {
            Objects.requireNonNull(definition, "definition");
            if (definition.phase() != phase) {
                continue;
            }
            if (definitionsById.containsKey(definition.id())) {
                throw new IllegalArgumentException("Duplicate lifecycle task id " + definition.id() + " in " + phase);
            }
            definitionsById.put(definition.id(), definition);
        }
        return definitionsById;
    }

    private static Map<LifecycleTaskId, LinkedHashSet<LifecycleTaskId>> dependencies(
            Map<LifecycleTaskId, LifecycleTaskDefinition> definitionsById) {
        Map<LifecycleTaskId, LinkedHashSet<LifecycleTaskId>> dependencies = new LinkedHashMap<>();
        for (LifecycleTaskId id : definitionsById.keySet()) {
            dependencies.put(id, new LinkedHashSet<>());
        }

        for (LifecycleTaskDefinition definition : definitionsById.values()) {
            for (LifecycleTaskId dependency : definition.after()) {
                requireKnownDependency(definitionsById, dependency, definition.id());
                dependencies.get(definition.id()).add(dependency);
            }
            for (LifecycleTaskId dependent : definition.before()) {
                requireKnownDependency(definitionsById, dependent, definition.id());
                dependencies.get(dependent).add(definition.id());
            }
        }
        return dependencies;
    }

    private static void requireKnownDependency(
            Map<LifecycleTaskId, LifecycleTaskDefinition> definitionsById,
            LifecycleTaskId dependency,
            LifecycleTaskId task) {
        if (!definitionsById.containsKey(dependency)) {
            throw new IllegalArgumentException(
                    "Unknown lifecycle task dependency " + dependency + " referenced by " + task);
        }
    }

    private static Map<LifecycleTaskId, LifecycleTaskDefinition> normalizedDefinitions(
            Map<LifecycleTaskId, LifecycleTaskDefinition> definitionsById,
            Map<LifecycleTaskId, LinkedHashSet<LifecycleTaskId>> dependencies) {
        Map<LifecycleTaskId, LifecycleTaskDefinition> normalizedDefinitions = new LinkedHashMap<>();
        for (Map.Entry<LifecycleTaskId, LifecycleTaskDefinition> entry : definitionsById.entrySet()) {
            LifecycleTaskDefinition definition = entry.getValue();
            normalizedDefinitions.put(entry.getKey(), new LifecycleTaskDefinition(
                    definition.phase(),
                    definition.id(),
                    definition.call(),
                    new ArrayList<>(dependencies.get(definition.id())),
                    definition.before(),
                    definition.waitForCompletionSecs()));
        }
        return normalizedDefinitions;
    }

    private static List<LifecycleTaskPlan.ExecutionGroup> executionGroups(
            Map<LifecycleTaskId, LifecycleTaskDefinition> definitionsById,
            Map<LifecycleTaskId, LinkedHashSet<LifecycleTaskId>> dependencies) {
        Set<LifecycleTaskId> remaining = new LinkedHashSet<>(definitionsById.keySet());
        List<LifecycleTaskPlan.ExecutionGroup> groups = new ArrayList<>();

        while (!remaining.isEmpty()) {
            List<LifecycleTaskId> ready = readyTasks(remaining, dependencies);
            if (ready.isEmpty()) {
                LifecycleTaskId firstRemaining = remaining.iterator().next();
                throw new IllegalArgumentException(
                        "Lifecycle task dependency cycle detected at " + firstRemaining);
            }

            List<LifecycleTaskDefinition> groupTasks = new ArrayList<>();
            for (LifecycleTaskId id : ready) {
                groupTasks.add(definitionsById.get(id));
            }
            groups.add(new LifecycleTaskPlan.ExecutionGroup(groupTasks));
            remaining.removeAll(ready);
        }
        return groups;
    }

    private static List<LifecycleTaskId> readyTasks(
            Set<LifecycleTaskId> remaining,
            Map<LifecycleTaskId, LinkedHashSet<LifecycleTaskId>> dependencies) {
        List<LifecycleTaskId> ready = new ArrayList<>();
        for (LifecycleTaskId id : remaining) {
            if (allDependenciesCompleted(remaining, dependencies.get(id))) {
                ready.add(id);
            }
        }
        return ready;
    }

    private static boolean allDependenciesCompleted(
            Set<LifecycleTaskId> remaining,
            LinkedHashSet<LifecycleTaskId> dependencies) {
        for (LifecycleTaskId dependency : dependencies) {
            if (remaining.contains(dependency)) {
                return false;
            }
        }
        return true;
    }
}
