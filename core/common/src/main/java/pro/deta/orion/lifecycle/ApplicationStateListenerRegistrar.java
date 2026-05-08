package pro.deta.orion.lifecycle;

import pro.deta.orion.ApplicationState;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.lifecycle.task.LifecycleTaskId;
import pro.deta.orion.lifecycle.task.LifecycleTaskRegistration;

import java.util.Objects;
import java.util.concurrent.Callable;


public interface ApplicationStateListenerRegistrar {
    default LifecycleTaskRegistration task(
            ApplicationState phase,
            LifecycleTaskId id,
            Callable<OrionStageCallResult> call) {
        return task(phase, id, null, call);
    }

    default LifecycleTaskRegistration task(
            Object service,
            ApplicationState phase,
            LifecycleTaskId id,
            Callable<OrionStageCallResult> call) {
        return task(phase, id, lifecycleServiceName(service), call);
    }

    default LifecycleTaskRegistration task(
            ApplicationState phase,
            LifecycleTaskId id,
            String serviceName,
            Callable<OrionStageCallResult> call) {
        LifecycleTaskRegistration registration = new LifecycleTaskRegistration(phase, id, serviceName, call);
        register(registration);
        return registration;
    }

    LifecycleTaskRegistration register(LifecycleTaskRegistration registration);

    private static String lifecycleServiceName(Object service) {
        Objects.requireNonNull(service, "service");
        String name = service.getClass().getSimpleName();
        if (name == null || name.isBlank()) {
            return service.getClass().getName();
        }
        return name;
    }
}
