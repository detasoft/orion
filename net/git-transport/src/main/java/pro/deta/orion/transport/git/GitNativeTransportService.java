package pro.deta.orion.transport.git;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;
import pro.deta.orion.schema.config.GitTransportConfig;

/**
 * Disabled legacy native Git TCP transport. Native Git serving is handled by
 * the blocking Jetty and SSH adapters.
 */
@Singleton
public class GitNativeTransportService implements ServiceLifecycleStateMachineAdapter.ServiceLifecycle {

    @Inject
    public GitNativeTransportService(
            GitTransportConfig config,
            NativeGitRepositoryProvider nativeRepositoryProvider) {
    }

    @Override
    public void onStart() {
    }

    @Override
    public void onStop() {
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public boolean isRunning() {
        return false;
    }
}
