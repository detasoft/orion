package pro.deta.orion.transport;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import jakarta.inject.Singleton;
import pro.deta.orion.config.schema.GitTransportConfig;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.config.schema.SshTransportConfig;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;
import pro.deta.orion.lifecycle.state.StateMachine;
import pro.deta.orion.transport.http.OrionHttpModule;

import javax.inject.Named;

@Module(includes = OrionHttpModule.class)
public class OrionTransportModule {
    @Provides
    @Singleton
    static GitTransportConfig gitTransportConfig(OrionConfiguration configuration) {
        OrionConfiguration.AppTransport transport = configuration.getTransport();
        if (transport == null || transport.getGit() == null) {
            GitTransportConfig disabled = new GitTransportConfig();
            disabled.setEnabled(false);
            return disabled;
        }
        return transport.getGit();
    }

    @Provides
    @Singleton
    static SshTransportConfig sshTransportConfig(OrionConfiguration configuration) {
        OrionConfiguration.AppTransport transport = configuration.getTransport();
        if (transport == null || transport.getSsh() == null) {
            SshTransportConfig disabled = new SshTransportConfig();
            disabled.setEnabled(false);
            return disabled;
        }
        return transport.getSsh();
    }

    @Provides
    @IntoSet
    static OrionApplicationStageEventListener transportLifecycleStateMachine(TransportLifecycleStateMachine stateMachine) {
        return stateMachine;
    }

    @Provides
    @Singleton
    @Named("transport")
    static StateMachine transportStateMachine(TransportLifecycleStateMachine stateMachine) {
        return stateMachine.stateMachine();
    }

    @Provides
    @IntoSet
    static OrionApplicationStageEventListener transportLifecycleBarrier(TransportLifecycleBarrier barrier) {
        return barrier;
    }
}
