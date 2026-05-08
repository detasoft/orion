package pro.deta.orion.component;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;

import jakarta.inject.Singleton;
import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.acl.storage.AccessControlStorage;
import pro.deta.orion.acl.storage.GitAccessControlStorage;
import pro.deta.orion.acl.storage.JDBCAccessControlStorage;
import pro.deta.orion.acl.storage.LocalAccessControlStorage;
import pro.deta.orion.acl.storage.LocalGitAccessControlStorage;
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

import javax.inject.Provider;
import java.net.URI;
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
    static OrionApplicationStageEventListener gitAccessControlStorage(GitAccessControlStorage gitAccessControlStorage) {
        return gitAccessControlStorage;
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
    static AccessControlStorage accessControlStorage(OrionConfiguration.ACLStorageType type,
                                                     OrionConfiguration.AccessControlConfig accessControlConfig,
                                                     GitRepositoryProviderImpl gitRepositoryProvider,
                                                     Provider<GitAccessControlStorage> gitAccessControlStorage,
                                                     JDBCAccessControlStorage jdbcAccessControlStorage,
                                                     LocalAccessControlStorage localAccessControlStorage) {
        return switch (type) {
            case GIT -> {
                if (isIndependentLocalGitStorage(accessControlConfig.getUrl())) {
                    yield new LocalGitAccessControlStorage(accessControlConfig);
                }
                if (isGitOverLocalRepositoryStorage(accessControlConfig.getUrl())) {
                    yield new LocalGitAccessControlStorage(
                            gitRepositoryProvider.repositoryPath(localRepositoryName(accessControlConfig.getUrl())),
                            accessControlConfig.getBranch(),
                            accessControlConfig.getSettingsFileName());
                }
                GitAccessControlStorage storage = gitAccessControlStorage.get();
                storage.setEnabled(true);
                yield storage;
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

    static boolean isIndependentLocalGitStorage(String location) {
        String scheme = URI.create(location).getScheme();
        return scheme == null || "file".equalsIgnoreCase(scheme);
    }

    static boolean isGitOverLocalRepositoryStorage(String location) {
        URI uri = URI.create(location);
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        return switch (scheme) {
            case "local" -> true;
            default -> false;
        };
    }

    static String localRepositoryName(String location) {
        URI uri = URI.create(location);
        StringBuilder repositoryName = new StringBuilder();
        if (uri.getHost() != null && !uri.getHost().isBlank()) {
            repositoryName.append(uri.getHost());
        }
        if (uri.getPath() != null && !uri.getPath().isBlank()) {
            if (!repositoryName.isEmpty()) {
                repositoryName.append("/");
            }
            repositoryName.append(stripLeadingSlashes(uri.getPath()));
        }
        if (repositoryName.isEmpty() && uri.getSchemeSpecificPart() != null) {
            repositoryName.append(stripLeadingSlashes(uri.getSchemeSpecificPart()));
        }
        return Path.of(repositoryName.toString()).normalize().toString();
    }

    private static String stripLeadingSlashes(String value) {
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }
}
