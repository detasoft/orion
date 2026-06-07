package pro.deta.orion.internal;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;

@Singleton
public final class OrionExecutorStateMachine extends ServiceLifecycleStateMachineAdapter {

    @Inject
    public OrionExecutorStateMachine(Provider<OrionExecutor> executorProvider) {
        super("executor", executorProvider);
    }
}
