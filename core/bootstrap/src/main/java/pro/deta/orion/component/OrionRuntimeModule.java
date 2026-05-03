package pro.deta.orion.component;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;

import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.eclipse.jgit.util.SystemReader;
import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.acl.storage.AccessControlStorage;
import pro.deta.orion.acl.storage.GitAccessControlStorage;
import pro.deta.orion.acl.storage.JDBCAccessControlStorage;
import pro.deta.orion.acl.storage.LocalAccessControlStorage;
import pro.deta.orion.config.*;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.git.GitRepositoryProviderImpl;
import pro.deta.orion.internal.GitInternalStorage;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.transport.git.GitNativeTransportService;
import pro.deta.orion.transport.git.GitSshTransportService;
import pro.deta.orion.transport.http.JettyHTTPServer;
import pro.deta.orion.util.ConfigurationContext;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;

import java.nio.file.Path;


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
    static OrionApplicationStageEventListener orionAccessControlServiceImpl(OrionAccessControlService orionAccessControlService) {
        return orionAccessControlService;
    }

    @Provides
    @IntoSet
    static OrionApplicationStageEventListener orionEventQueue(OrionExecutor orionEvent) {
        return orionEvent;
    }

    @Provides
    @IntoSet
    static OrionApplicationStageEventListener gitAccessControlStorage(GitAccessControlStorage gitAccessControlStorage) {
        return gitAccessControlStorage;
    }

    @Provides
    @IntoSet
    static OrionApplicationStageEventListener gitInternalStorage(GitInternalStorage gitInternalStorage) {
        return gitInternalStorage;
    }

    @Provides
    @IntoSet
    static OrionApplicationStageEventListener orionEventManager(OrionEventManager orionEventManager) {
        return orionEventManager;
    }

    @Provides
    @WorkDir
    Path workDir(ConfigurationContext configurationContext) {
        return configurationContext.getWorkDir();
    }

    @Provides
    @BaseDir
    Path baseDir(ConfigurationContext configurationContext) {
        return configurationContext.getBaseDir();
    }

    @Provides
    @ThreadPoolSizeConfig
    Integer executorPoolSize(OrionConfiguration orionConfiguration) {
        return orionConfiguration.getThreadPoolSize();
    }

    @Provides
    OrionAccessControlService orionAccessControlService(OrionAccessControlServiceImpl orionAccessControlService) {
        return orionAccessControlService;
    };

    @Provides
    @GitStorageDir
    Path gitStorageDir(ConfigurationContext configurationContext) {
        return configurationContext.getGitStoragePath();
    }


    @Provides
    @Named("default")
    SystemReader localGitServerSystemReader() {
        return SystemReader.getInstance();
    }

//    OrionConfiguration.ACLStorageType storageType(OrionConfiguration orionConfiguration) {
//        return orionConfiguration.getAccessControl().getType();
//    }

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
    OrionConfiguration.ACLStorageType aclStoragType(OrionConfiguration orionConfiguration) {
        return orionConfiguration.getAccessControl().getType();
    }

    @Provides
    AccessControlStorage accessControlStorage(OrionConfiguration.ACLStorageType type, GitAccessControlStorage gitAccessControlStorage, JDBCAccessControlStorage jdbcAccessControlStorage, LocalAccessControlStorage localAccessControlStorage) {
        return switch (type) {
            case GIT -> {
                gitAccessControlStorage.setEnabled(true);
                yield gitAccessControlStorage;
            }
            case JDBC -> {
                jdbcAccessControlStorage.setEnabled(true);
                yield jdbcAccessControlStorage;
            }
            case LOCAL -> {
                localAccessControlStorage.setEnabled(true);
                yield localAccessControlStorage;
            }
        };
    }
}
