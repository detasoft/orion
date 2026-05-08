package pro.deta.orion.lifecycle;

import pro.deta.orion.ApplicationState;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.lifecycle.listener.RegisteredListener;
import pro.deta.orion.lifecycle.task.LifecycleTaskId;
import pro.deta.orion.lifecycle.task.LifecycleTaskRegistration;

import java.util.concurrent.Callable;


public interface ApplicationStateListenerRegistrar {

    default RegisteredListener register(ApplicationState state, Callable<OrionStageCallResult> call) {
        return register(new RegisteredListener(state, call));
    }

    default LifecycleTaskRegistration task(
            ApplicationState phase,
            LifecycleTaskId id,
            Callable<OrionStageCallResult> call) {
        LifecycleTaskRegistration registration = new LifecycleTaskRegistration(phase, id, call);
        register(registration);
        return registration;
    }

    RegisteredListener register(RegisteredListener listener);

    LifecycleTaskRegistration register(LifecycleTaskRegistration registration);
}
