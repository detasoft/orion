package pro.deta.orion.transport.git;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import pro.deta.orion.lifecycle.OrionLifecycleStateMachine;

@Module
public class OrionGitTransportModule {
    @Provides
    @IntoSet
    static OrionLifecycleStateMachine gitNativeTransportStateMachine(GitNativeTransportStateMachine stateMachine) {
        return stateMachine;
    }
}
