package pro.deta.orion.acl;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;

@Singleton
public final class OrionAccessControlStateMachine extends ServiceLifecycleStateMachineAdapter {

    @Inject
    public OrionAccessControlStateMachine(Provider<OrionAccessControlServiceImpl> accessControlProvider) {
        super("access-control", accessControlProvider);
    }
}
