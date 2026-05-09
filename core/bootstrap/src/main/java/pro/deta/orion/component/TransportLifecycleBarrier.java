package pro.deta.orion.component;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.lifecycle.task.LifecycleTaskRegistration;
import pro.deta.orion.lifecycle.task.OrionLifecycleTasks;

@Singleton
public final class TransportLifecycleBarrier implements OrionApplicationStageEventListener {
    private final OrionConfiguration configuration;

    @Inject
    public TransportLifecycleBarrier(OrionConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public void registerToStage(ApplicationStateListenerRegistrar registrar) {
        registrar.task(this, ApplicationState.STARTING, OrionLifecycleTasks.TRANSPORTS_START, () -> OrionStageCallResult.EMPTY)
                .after(OrionLifecycleTasks.ACL_LOAD);
        LifecycleTaskRegistration transportsStop = registrar.task(
                this,
                ApplicationState.STOPPING,
                OrionLifecycleTasks.TRANSPORTS_STOP,
                () -> OrionStageCallResult.EMPTY);
        if (TransportRuntimeConfig.isHttpTransportEnabled(configuration)) {
            transportsStop.after(OrionLifecycleTasks.HTTP_TRANSPORT_STOP);
        }
        if (TransportRuntimeConfig.isGitTransportEnabled(configuration)) {
            transportsStop.after(OrionLifecycleTasks.GIT_TRANSPORT_STOP);
        }
        if (TransportRuntimeConfig.isSshTransportEnabled(configuration)) {
            transportsStop.after(OrionLifecycleTasks.SSH_TRANSPORT_STOP);
        }
    }
}
