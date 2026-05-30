package pro.deta.orion.component;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dagger.multibindings.IntoSet;

import jakarta.inject.Singleton;
import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.acl.storage.AccessControlStorage;
import pro.deta.orion.acl.storage.AccessControlStorageResolver;
import pro.deta.orion.config.ConfigurationProvider;
import pro.deta.orion.config.schema.GitTransportConfig;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.crypto.PublicKeysProvider;
import pro.deta.orion.crypto.ServerIdentityKeyService;
import pro.deta.orion.crypto.ServerKeySigner;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.git.OrionJGitRuntime;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.transport.git.GitSshTransportService;
import pro.deta.orion.transport.http.JettyHTTPServer;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;

import javax.inject.Provider;
import java.security.PublicKey;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;


@Module
public class OrionRuntimeModule {
    @Provides
    @Singleton
    static OrionConfiguration orionConfiguration(ConfigurationProvider configurationProvider) {
        return configurationProvider.readConfiguration();
    }

    @Provides
    @Singleton
    static GitTransportConfig gitTransportConfig(OrionConfiguration configuration) {
        OrionConfiguration.AppTransport transport = configuration.getTransport();
        if (transport == null || transport.getGit() == null) {
            GitTransportConfig disabled = new GitTransportConfig(null, 9418);
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
    @IntoSet
    static OrionApplicationStageEventListener transportLifecycleBarrier(TransportLifecycleBarrier barrier) {
        return barrier;
    }

    @Provides
    @IntoSet
    static OrionApplicationStageEventListener orionAccessControlServiceImpl(OrionAccessControlServiceImpl orionAccessControlService) {
        return orionAccessControlService;
    }

    @Provides
    @IntoSet
    static OrionApplicationStageEventListener orionEventQueue(OrionExecutor orionEvent) {
        return orionEvent;
    }

    @Provides
    @IntoSet
    static OrionApplicationStageEventListener orionEventManager(OrionEventManager orionEventManager) {
        return orionEventManager;
    }

    @Provides
    @IntoSet
    static OrionApplicationStageEventListener orionJGitRuntime(OrionJGitRuntime orionJGitRuntime) {
        return orionJGitRuntime;
    }

    @Provides
    OrionAccessControlService orionAccessControlService(OrionAccessControlServiceImpl orionAccessControlService) {
        return orionAccessControlService;
    };

    @Provides
    @Singleton
    GitRepositoryProvider defaultGitRepositoryProvider(GitRepositoryProviderResolver resolver) {
        return resolver.resolve();
    }

    @Provides
    @Singleton
    PublicKeysProvider publicKeysProvider(Provider<ServerIdentityKeyService> serverIdentityKeyService) {
        return new PublicKeysProvider() {
            @Override
            public Collection<PublicKey> getPublicKeys() {
                return serverIdentityKeyService.get().getPublicKeys();
            }
        };
    }

    @Provides
    @Singleton
    ServerKeySigner serverKeySigner(Provider<ServerIdentityKeyService> serverIdentityKeyService) {
        return new ServerKeySigner() {
            @Override
            public SigningKey rsaSha256SigningKey() {
                return serverIdentityKeyService.get().rsaSha256SigningKey();
            }
        };
    }

    @Provides
    static AccessControlStorage accessControlStorage(AccessControlStorageResolver accessControlStorageResolver) {
        return accessControlStorageResolver.resolve();
    }
}
