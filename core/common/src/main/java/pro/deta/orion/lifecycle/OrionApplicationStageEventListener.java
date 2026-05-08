package pro.deta.orion.lifecycle;

import pro.deta.orion.ApplicationState;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.lifecycle.task.LifecycleTaskId;
import pro.deta.orion.lifecycle.task.LifecycleTaskRegistration;

import java.util.concurrent.Callable;

public interface OrionApplicationStageEventListener {
    void registerToStage(ApplicationStateListenerRegistrar registrar);

    default LifecycleTaskRegistration task(
            ApplicationStateListenerRegistrar registrar,
            ApplicationState phase,
            LifecycleTaskId id,
            Callable<OrionStageCallResult> call) {
        return registrar.task(phase, id, lifecycleServiceName(), call);
    }

    default String lifecycleServiceName() {
        String name = getClass().getSimpleName();
        if (name == null || name.isBlank()) {
            return getClass().getName();
        }
        return name;
    }
}
