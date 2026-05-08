package pro.deta.orion.lifecycle;

import pro.deta.orion.ApplicationState;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.lifecycle.task.LifecycleTaskId;
import pro.deta.orion.lifecycle.task.LifecycleTaskRegistration;

import java.util.concurrent.Callable;


public interface ApplicationStateListenerRegistrar {
    default LifecycleTaskRegistration task(
            ApplicationState phase,
            LifecycleTaskId id,
            Callable<OrionStageCallResult> call) {
        LifecycleTaskRegistration registration = new LifecycleTaskRegistration(phase, id, call);
        register(registration);
        return registration;
    }

    LifecycleTaskRegistration register(LifecycleTaskRegistration registration);
}
