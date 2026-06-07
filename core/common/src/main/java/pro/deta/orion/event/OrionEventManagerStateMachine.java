package pro.deta.orion.event;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;

@Singleton
public final class OrionEventManagerStateMachine extends ServiceLifecycleStateMachineAdapter {

    @Inject
    public OrionEventManagerStateMachine(Provider<OrionEventManager> eventManagerProvider) {
        super("event-manager", eventManagerProvider);
    }
}
