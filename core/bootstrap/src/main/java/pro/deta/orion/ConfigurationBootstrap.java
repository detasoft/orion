package pro.deta.orion;

import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.config.NativeGitOrionConfigurationStorage;
import pro.deta.orion.config.OrionVersionedConfigurationStorage;
import pro.deta.orion.git.proxy.NativeGitRepositoryFactory;

/** Creates configuration storage from the resolved bootstrap source. */
public final class ConfigurationBootstrap {
    private ConfigurationBootstrap() {
    }

    public static OrionVersionedConfigurationStorage open(
            BootstrapConfiguration configuration,
            NativeGitRepositoryFactory repositoryFactory) {
        return new NativeGitOrionConfigurationStorage(repositoryFactory.fileEvents(
                NativeGitRepositoryFactory.CONFIGURATION_SOURCE, configuration.getBootstrap().getAccessControl()));
    }
}
