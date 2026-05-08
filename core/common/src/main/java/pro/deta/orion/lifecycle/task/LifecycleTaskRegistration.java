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
    private final Callable<OrionStageCallResult> call;
    private final List<LifecycleTaskId> after = new ArrayList<>();
    private final List<LifecycleTaskId> before = new ArrayList<>();
    private LifecycleRunMode runMode = LifecycleRunMode.NON_BLOCKING;
    private int waitForCompletionSecs;

    public LifecycleTaskRegistration(
            ApplicationState phase,
            LifecycleTaskId id,
            Callable<OrionStageCallResult> call) {
        this.phase = Objects.requireNonNull(phase, "phase");
        this.id = Objects.requireNonNull(id, "id");
        this.call = Objects.requireNonNull(call, "call");
    }

    public LifecycleTaskRegistration after(LifecycleTaskId dependency) {
        after.add(Objects.requireNonNull(dependency, "dependency"));
        return this;
    }

    public LifecycleTaskRegistration before(LifecycleTaskId dependent) {
        before.add(Objects.requireNonNull(dependent, "dependent"));
        return this;
    }

    public LifecycleTaskRegistration runMode(LifecycleRunMode runMode) {
        this.runMode = Objects.requireNonNull(runMode, "runMode");
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
                call,
                after,
                before,
                runMode,
                waitForCompletionSecs);
    }
}
