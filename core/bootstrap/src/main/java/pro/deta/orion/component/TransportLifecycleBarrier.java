package pro.deta.orion.component;

import pro.deta.orion.ApplicationState;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.lifecycle.task.OrionLifecycleTasks;

public final class TransportLifecycleBarrier implements OrionApplicationStageEventListener {
    @Override
    public void registerToStage(ApplicationStateListenerRegistrar registrar) {
        task(registrar, ApplicationState.STARTING, OrionLifecycleTasks.TRANSPORTS_START, () -> OrionStageCallResult.EMPTY)
                .after(OrionLifecycleTasks.ACL_LOAD);
        task(registrar, ApplicationState.STOPPING, OrionLifecycleTasks.TRANSPORTS_STOP, () -> OrionStageCallResult.EMPTY);
    }
}
