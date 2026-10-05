package pro.deta.orion.transport;

import dagger.Module;
import dagger.Provides;
import jakarta.inject.Singleton;
import pro.deta.orion.bootstrap.config.GitTransportConfig;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.bootstrap.config.SshTransportConfig;
import pro.deta.orion.transport.git.command.SshCommandModule;
import pro.deta.orion.transport.http.OrionHttpModule;

@Module(includes = {OrionHttpModule.class, SshCommandModule.class})
public class OrionTransportModule {
    @Provides
    @Singleton
    static GitTransportConfig gitTransportConfig(BootstrapConfiguration configuration) {
        BootstrapConfiguration.AppTransport transport = configuration.getTransport();
        if (transport == null || transport.getGit() == null) {
            GitTransportConfig disabled = new GitTransportConfig();
            disabled.setEnabled(false);
            return disabled;
        }
        return transport.getGit();
    }

    @Provides
    @Singleton
    static SshTransportConfig sshTransportConfig(BootstrapConfiguration configuration) {
        BootstrapConfiguration.AppTransport transport = configuration.getTransport();
        if (transport == null || transport.getSsh() == null) {
            SshTransportConfig disabled = new SshTransportConfig();
            disabled.setEnabled(false);
            return disabled;
        }
        return transport.getSsh();
    }

}
