package pro.deta.orion.lifecycle.flow;

import pro.deta.orion.ApplicationState;

import java.util.List;
import java.util.Objects;

public record LifecycleFlow(String name, List<LifecycleStep> steps) {
    public static final LifecycleFlow STARTUP = new LifecycleFlow("STARTUP", List.of(
            LifecycleStep.from(ApplicationState.INIT)
                    .to(ApplicationState.STARTING)
                    .onFailure(ApplicationState.FAILED)
                    .build(),
            LifecycleStep.from(ApplicationState.STARTING)
                    .to(ApplicationState.UP)
                    .onFailure(ApplicationState.FAILED)
                    .build()));

    public static final LifecycleFlow SHUTDOWN = new LifecycleFlow("SHUTDOWN", List.of(
            LifecycleStep.from(ApplicationState.UP)
                    .to(ApplicationState.BEGIN_SHUTDOWN)
                    .onFailure(ApplicationState.FAILED)
                    .transitionOnly()
                    .build(),
            LifecycleStep.from(ApplicationState.BEGIN_SHUTDOWN)
                    .to(ApplicationState.STOPPING)
                    .onFailure(ApplicationState.FAILED)
                    .transitionOnly()
                    .build(),
            LifecycleStep.from(ApplicationState.STOPPING)
                    .to(ApplicationState.OFF)
                    .onFailure(ApplicationState.FAILED)
                    .build()));

    public LifecycleFlow {
        Objects.requireNonNull(name, "name");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("Lifecycle flow must contain at least one step");
        }
        validateContinuousFlow(name, steps);
    }

    private static void validateContinuousFlow(String name, List<LifecycleStep> steps) {
        for (int i = 1; i < steps.size(); i++) {
            LifecycleStep previous = steps.get(i - 1);
            LifecycleStep current = steps.get(i);
            if (previous.success() != current.from()) {
                throw new IllegalArgumentException(
                        "Lifecycle flow " + name + " is not continuous: step " + i
                                + " starts from " + current.from()
                                + " but previous step succeeds to " + previous.success());
            }
        }
    }

    public String describe() {
        StringBuilder builder = new StringBuilder(name).append(":\n");
        for (LifecycleStep step : steps) {
            builder.append("  ")
                    .append(step.from())
                    .append(" -> ")
                    .append(step.success())
                    .append('\n');
        }
        for (LifecycleStep step : steps) {
            builder.append("  ")
                    .append(step.from())
                    .append(" -> ")
                    .append(step.failure())
                    .append(" on failure\n");
        }
        return builder.toString();
    }
}
