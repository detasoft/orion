package pro.deta.orion.transport;

import dagger.Module;
import dagger.Provides;
import jakarta.inject.Singleton;
import pro.deta.orion.schema.config.GitTransportConfig;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.config.SshTransportConfig;
import pro.deta.orion.git.nativestorage.FileNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryService;
import pro.deta.orion.transport.git.DefaultGitNativeRepositoryService;
import pro.deta.orion.transport.http.OrionHttpModule;
import pro.deta.orion.util.ConfigurationContext;

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
    @Singleton
    static NativeGitRepositoryProvider nativeGitRepositoryProvider(
            ConfigurationContext configurationContext) {
        try {
            return new FileNativeGitRepositoryProvider(
                    configurationContext.getFileGitStoragePath());
        } catch (IllegalArgumentException ignored) {
            return new InMemoryNativeGitRepositoryProvider();
        }
    }

    @Provides
    @Singleton
    static GitNativeRepositoryService gitNativeRepositoryService(
            DefaultGitNativeRepositoryService service) {
        return service;
    }
}
