package pro.deta.orion.transport;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.lifecycle.task.LifecycleTaskRegistration;
import pro.deta.orion.lifecycle.task.OrionLifecycleTasks;

/**
 * Legacy application lifecycle bridge for the transport phase boundaries.
 *
 * <p>{@code TRANSPORTS_START} and {@code TRANSPORTS_STOP} are ordering anchors for services that must start before
 * or stop after all transport endpoints. This class does not start or stop endpoints; the concrete transport aggregate
 * is owned by {@link TransportLifecycleStateMachine} through {@code TRANSPORT_LIFECYCLE_START} and
 * {@code TRANSPORT_LIFECYCLE_STOP}.</p>
 *
 * <p>@AiRule Keep this class as a no-op phase bridge until the application lifecycle task graph is replaced by a root
 * lifecycle state machine.</p>
 */
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
        if (TransportRuntimeConfig.isHttpTransportEnabled(configuration)
                || TransportRuntimeConfig.isGitTransportEnabled(configuration)
                || TransportRuntimeConfig.isSshTransportEnabled(configuration)) {
            transportsStop.after(OrionLifecycleTasks.TRANSPORT_LIFECYCLE_STOP);
        }
    }
}
