package pro.deta.orion.transport;

import dagger.Module;
import dagger.Provides;
import jakarta.inject.Singleton;
import pro.deta.orion.schema.config.GitTransportConfig;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.config.SshTransportConfig;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryService;
import pro.deta.orion.transport.git.DefaultGitNativeRepositoryService;
import pro.deta.orion.transport.git.command.SshCommandModule;
import pro.deta.orion.transport.http.OrionHttpModule;

@Module(includes = {OrionHttpModule.class, SshCommandModule.class})
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
    static GitNativeRepositoryService gitNativeRepositoryService(
            DefaultGitNativeRepositoryService service) {
        return service;
    }
}
