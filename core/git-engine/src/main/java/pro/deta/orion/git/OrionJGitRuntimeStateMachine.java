package pro.deta.orion.git;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;

@Singleton
public final class OrionJGitRuntimeStateMachine extends ServiceLifecycleStateMachineAdapter {

    @Inject
    public OrionJGitRuntimeStateMachine(Provider<OrionJGitRuntime> runtimeProvider) {
        super("jgit-runtime", runtimeProvider);
    }
}
