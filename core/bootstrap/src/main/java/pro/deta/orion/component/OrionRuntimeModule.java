package pro.deta.orion.component;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;

import jakarta.inject.Singleton;
import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.acl.storage.AccessControlStorage;
import pro.deta.orion.acl.storage.AccessControlStorageResolver;
import pro.deta.orion.config.ConfigurationProvider;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.git.GitRepositoryProviderImpl;
import pro.deta.orion.git.OrionJGitRuntime;
import pro.deta.orion.git.storage.GitBackedInternalStorage;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.transport.git.GitNativeTransportService;
import pro.deta.orion.transport.git.GitSshTransportService;
import pro.deta.orion.transport.http.JettyHTTPServer;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;


@Module
public class OrionRuntimeModule {
    @Provides
    @Singleton
    static OrionConfiguration orionConfiguration(ConfigurationProvider configurationProvider) {
        return configurationProvider.readConfiguration();
    }

    @Provides
    @IntoSet
    static OrionApplicationStageEventListener jettyHttpServer(JettyHTTPServer jettyHTTPServer) {
        return jettyHTTPServer;
    }

    @Provides
    @IntoSet
    static OrionApplicationStageEventListener gitNativeTransportService(GitNativeTransportService gitNativeTransportService) {
        return gitNativeTransportService;
    }

    @Provides
    @IntoSet
    static OrionApplicationStageEventListener gitSshTransportService(GitSshTransportService gitSshTransportService) {
        return gitSshTransportService;
    }

    @Provides
    @IntoSet
    static OrionApplicationStageEventListener transportLifecycleBarrier() {
        return new TransportLifecycleBarrier();
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
    static OrionApplicationStageEventListener gitBackedInternalStorage(GitBackedInternalStorage gitBackedInternalStorage) {
        return gitBackedInternalStorage;
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
    GitRepositoryProvider defaultGitRepositoryProvider(GitRepositoryProviderImpl impl) {
        return impl;
    }

    @Provides
    OrionConfiguration.AccessControlConfig accessControlConfig(OrionConfiguration orionConfiguration) {
        return orionConfiguration.getAccessControl();
    }

    @Provides
    static AccessControlStorage accessControlStorage(AccessControlStorageResolver accessControlStorageResolver) {
        return accessControlStorageResolver.resolve();
    }
}
