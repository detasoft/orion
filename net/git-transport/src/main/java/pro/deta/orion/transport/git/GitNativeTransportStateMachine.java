package pro.deta.orion.transport.git;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;

import jakarta.inject.Provider;

/**
 * @AiRule This standalone transport adapter intentionally exposes its raw StateMachine as production API.
 */
@Singleton
public final class GitNativeTransportStateMachine extends ServiceLifecycleStateMachineAdapter {

    @Inject
    public GitNativeTransportStateMachine(
            Provider<GitNativeTransportService> serviceProvider) {
        super("git-native", serviceProvider);
    }
}
