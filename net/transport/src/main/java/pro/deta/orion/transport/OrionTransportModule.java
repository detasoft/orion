package pro.deta.orion.transport;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dagger.multibindings.IntoSet;
import jakarta.inject.Singleton;
import pro.deta.orion.config.schema.GitTransportConfig;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;
import pro.deta.orion.lifecycle.state.StateMachine;
import pro.deta.orion.transport.git.GitSshTransportService;
import pro.deta.orion.transport.http.JettyHTTPServer;
import pro.deta.orion.transport.http.OrionHttpModule;

import javax.inject.Named;
import javax.inject.Provider;
import java.util.LinkedHashSet;
import java.util.Set;

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
    @ElementsIntoSet
    static Set<OrionApplicationStageEventListener> transportServices(
            OrionConfiguration configuration,
            Provider<JettyHTTPServer> jettyHttpServer,
            Provider<GitSshTransportService> gitSshTransportService) {
        Set<OrionApplicationStageEventListener> services = new LinkedHashSet<>();
        if (TransportRuntimeConfig.isHttpTransportEnabled(configuration)) {
            services.add(jettyHttpServer.get());
        }
        if (TransportRuntimeConfig.isSshTransportEnabled(configuration)) {
            services.add(gitSshTransportService.get());
        }
        return services;
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
