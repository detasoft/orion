package pro.deta.orion.component;

import dagger.BindsInstance;
import dagger.Component;

import jakarta.inject.Named;
import jakarta.inject.Singleton;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.BootstrapRepositorySources;
import pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.ResolvedBootstrapSource;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;
import pro.deta.orion.lifecycle.state.AggregateStateMachine;
import pro.deta.orion.lifecycle.state.TestOnly;
import pro.deta.orion.keymaterial.ServerIdentityCapability;
import pro.deta.orion.keymaterial.AcmeKeyMaterialCapability;
import pro.deta.orion.keymaterial.TlsCapability;
import pro.deta.orion.schema.config.ConfigurationProvider;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.config.OrionRuntimeOptions;
import pro.deta.orion.transport.OrionTransportModule;

import java.util.List;

@Singleton
@Component(modules = {OrionRuntimeModule.class, OrionTransportModule.class})
public interface OrionComponent {

    OrionApplicationLifecycle orionApplicationLifecycle();

    OrionAccessControlServiceImpl orionAccessControlService();

    @TestOnly
    NativeGitRepositoryProvider nativeGitRepositoryProvider();

    @Named("runtime")
    AggregateStateMachine runtimeStateMachine();

    @Component.Builder
    interface Builder {
        OrionComponent build();
        @BindsInstance Builder configurationProvider(ConfigurationProvider configurationProvider);
        @BindsInstance Builder runtimeOptions(OrionRuntimeOptions runtimeOptions);
        @BindsInstance Builder serverIdentityCapability(ServerIdentityCapability serverIdentityCapability);
        @BindsInstance Builder acmeKeyMaterialCapability(AcmeKeyMaterialCapability capability);
        @BindsInstance Builder tlsCapability(TlsCapability capability);
        @BindsInstance Builder nativeGitRepositoryProvider(NativeGitRepositoryProvider repositoryProvider);
        @BindsInstance Builder bootstrapRepositorySources(BootstrapRepositorySources repositorySources);

        default Builder defaultConfigurationProvider() {
            OrionConfiguration configuration = new OrionConfiguration();
            ProxyAwareNativeGitRepositoryProvider repositoryProvider =
                    new ProxyAwareNativeGitRepositoryProvider(new InMemoryNativeGitRepositoryProvider());
            ResolvedBootstrapSource configurationSource = repositoryProvider.resolveProvisional(
                    BootstrapRepositorySources.CONFIGURATION,
                    configuration.getBootstrap().getAccessControl(),
                    true);
            return configurationProvider(() -> configuration)
                    .runtimeOptions(OrionRuntimeOptions.defaults())
                    .serverIdentityCapability(ServerIdentityCapability.unavailable())
                    .acmeKeyMaterialCapability(AcmeKeyMaterialCapability.unavailable())
                    .tlsCapability(TlsCapability.unavailable())
                    .nativeGitRepositoryProvider(repositoryProvider)
                    .bootstrapRepositorySources(new BootstrapRepositorySources(List.of(configurationSource)));
        }
    }
}
