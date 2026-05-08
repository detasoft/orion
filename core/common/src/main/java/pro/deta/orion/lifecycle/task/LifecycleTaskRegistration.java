package pro.deta.orion.lifecycle.task;

import pro.deta.orion.ApplicationState;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

public final class LifecycleTaskRegistration {
    private final ApplicationState phase;
    private final LifecycleTaskId id;
    private final String serviceName;
    private final Callable<OrionStageCallResult> call;
    private final List<LifecycleTaskId> after = new ArrayList<>();
    private int waitForCompletionSecs;

    public LifecycleTaskRegistration(
            ApplicationState phase,
            LifecycleTaskId id,
            Callable<OrionStageCallResult> call) {
        this(phase, id, null, call);
    }

    public LifecycleTaskRegistration(
            ApplicationState phase,
            LifecycleTaskId id,
            String serviceName,
            Callable<OrionStageCallResult> call) {
        this.phase = Objects.requireNonNull(phase, "phase");
        this.id = Objects.requireNonNull(id, "id");
        this.serviceName = serviceName == null ? "" : serviceName;
        this.call = Objects.requireNonNull(call, "call");
    }

    public LifecycleTaskRegistration after(LifecycleTaskId dependency) {
        after.add(Objects.requireNonNull(dependency, "dependency"));
        return this;
    }

    public LifecycleTaskRegistration waitForCompletionSecs(int seconds) {
        waitForCompletionSecs = seconds;
        return this;
    }

    public LifecycleTaskDefinition definition() {
        return new LifecycleTaskDefinition(
                phase,
                id,
                serviceName,
                call,
                after,
                waitForCompletionSecs);
    }
}
