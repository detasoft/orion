package pro.deta.orion.lifecycle.task;

import pro.deta.orion.ApplicationState;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

public record LifecycleTaskDefinition(
        ApplicationState phase,
        LifecycleTaskId id,
        Callable<OrionStageCallResult> call,
        List<LifecycleTaskId> after,
        int waitForCompletionSecs
) {
    public LifecycleTaskDefinition {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(call, "call");
        after = List.copyOf(Objects.requireNonNull(after, "after"));
    }
}
