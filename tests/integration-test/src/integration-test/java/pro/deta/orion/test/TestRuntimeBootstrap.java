package pro.deta.orion.test;

import pro.deta.orion.component.DaggerOrionComponent;
import pro.deta.orion.component.OrionComponent;
import pro.deta.orion.git.nativestorage.FileNativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.BootstrapRepositorySources;
import pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.ResolvedBootstrapSource;
import pro.deta.orion.keymaterial.ServerIdentityCapability;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.config.OrionRuntimeOptions;
import pro.deta.orion.util.ConfigurationContext;

import java.util.List;
import java.util.Map;

final class TestRuntimeBootstrap {
    private TestRuntimeBootstrap() {
    }

    static OrionComponent.Builder componentBuilder(
            OrionConfiguration configuration,
            ServerIdentityCapability identity) {
        return componentBuilder(configuration, identity, OrionRuntimeOptions.defaults());
    }

    static OrionComponent.Builder componentBuilder(
            OrionConfiguration configuration,
            ServerIdentityCapability identity,
            OrionRuntimeOptions runtimeOptions) {
        FileNativeGitRepositoryProvider backend = new FileNativeGitRepositoryProvider(
                new ConfigurationContext(configuration).getFileGitStoragePath());
        ProxyAwareNativeGitRepositoryProvider provider =
                ProxyAwareNativeGitRepositoryProvider.bootstrap(backend, Map.copyOf(System.getenv()));
        ResolvedBootstrapSource source = provider.resolveProvisional(
                BootstrapRepositorySources.CONFIGURATION,
                configuration.getBootstrap().getAccessControl(),
                true);
        return DaggerOrionComponent.builder()
                .configurationProvider(() -> configuration)
                .runtimeOptions(runtimeOptions)
                .serverIdentityCapability(identity)
                .nativeGitRepositoryProvider(provider)
                .bootstrapRepositorySources(new BootstrapRepositorySources(List.of(source)));
    }
}
