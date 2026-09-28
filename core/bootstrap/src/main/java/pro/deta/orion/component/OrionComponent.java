package pro.deta.orion.component;

import dagger.BindsInstance;
import dagger.Component;

import jakarta.inject.Named;
import jakarta.inject.Singleton;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.acl.storage.AccessControlSnapshot;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.keymaterial.ConfigurationCipherCapability;
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
import pro.deta.orion.keymaterial.ConfigurationMaterialCapability;
import pro.deta.orion.keymaterial.SshHostKeyCapability;
import pro.deta.orion.keymaterial.TlsCapability;
import pro.deta.orion.schema.config.ConfigurationProvider;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.config.OrionRuntimeOptions;
import pro.deta.orion.transport.OrionTransportModule;
import pro.deta.orion.transport.git.GitNativeTransportService;
import pro.deta.orion.transport.git.GitSshTransportService;
import pro.deta.orion.transport.http.JettyHTTPServer;

import java.util.List;
import java.util.Optional;

@Singleton
@Component(modules = {OrionRuntimeModule.class, OrionTransportModule.class})
public interface OrionComponent {

    OrionApplicationLifecycle orionApplicationLifecycle();

    OrionAccessControlServiceImpl orionAccessControlService();

    @TestOnly
    ConfigurationSecrets configurationSecrets();

    @TestOnly
    NativeGitRepositoryProvider nativeGitRepositoryProvider();

    @TestOnly
    GitNativeTransportService nativeGitTransport();

    @TestOnly
    GitSshTransportService sshTransport();

    @TestOnly
    JettyHTTPServer httpTransport();

    @Named("runtime")
    AggregateStateMachine runtimeStateMachine();

    @Component.Builder
    interface Builder {
        OrionComponent build();
        @BindsInstance Builder configurationProvider(ConfigurationProvider configurationProvider);
        @BindsInstance Builder runtimeOptions(OrionRuntimeOptions runtimeOptions);
        @BindsInstance Builder serverIdentityCapability(ServerIdentityCapability serverIdentityCapability);
        @BindsInstance Builder acmeKeyMaterialCapability(AcmeKeyMaterialCapability capability);
        @BindsInstance Builder configurationMaterialCapability(ConfigurationMaterialCapability capability);
        @BindsInstance Builder initialConfiguration(Optional<AccessControlSnapshot> snapshot);
        @BindsInstance Builder configurationCipherCapability(ConfigurationCipherCapability capability);
        @BindsInstance Builder tlsCapability(TlsCapability capability);
        @BindsInstance Builder sshHostKeyCapability(SshHostKeyCapability capability);
        @BindsInstance Builder nativeGitRepositoryProvider(ProxyAwareNativeGitRepositoryProvider repositoryProvider);
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
                    .configurationMaterialCapability(ConfigurationMaterialCapability.unavailable())
                    .initialConfiguration(Optional.empty())
                    .configurationCipherCapability(ConfigurationCipherCapability.unavailable())
                    .tlsCapability(TlsCapability.unavailable())
                    .sshHostKeyCapability(SshHostKeyCapability.unavailable())
                    .nativeGitRepositoryProvider(repositoryProvider)
                    .bootstrapRepositorySources(new BootstrapRepositorySources(List.of(configurationSource)));
        }
    }
}
