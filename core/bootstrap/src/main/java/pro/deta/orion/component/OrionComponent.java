package pro.deta.orion.component;

import pro.deta.orion.KeyMaterialBootstrap;

import pro.deta.orion.event.OrionEventManager;

import pro.deta.orion.config.OrionConfigurationEditor;
import dagger.BindsInstance;
import dagger.Component;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.NativeGitRepositoryFactory;
import pro.deta.orion.git.s3.ConfiguredNativeGitRepositoryFactory;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend;
import pro.deta.orion.git.s3.S3Transport;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;
import pro.deta.orion.lifecycle.state.AggregateStateMachine;
import pro.deta.orion.lifecycle.state.TestOnly;
import pro.deta.orion.keymaterial.SigningMaterialSet;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.acl.RootPasswordReset;
import pro.deta.orion.transport.OrionTransportModule;
import pro.deta.orion.transport.git.GitNativeTransportService;
import pro.deta.orion.transport.git.GitSshTransportService;
import pro.deta.orion.transport.http.JettyHTTPServer;

@Singleton
@Component(modules = {OrionRuntimeModule.class, OrionTransportModule.class})
public interface OrionComponent {

    OrionApplicationLifecycle orionApplicationLifecycle();

    OrionAccessControlServiceImpl orionAccessControlService();

    @TestOnly
    OrionConfigurationEditor configurationEditor();

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

    S3Transport s3Transport();

    @Component.Builder
    interface Builder {
        OrionComponent build();
        @BindsInstance Builder bootstrapConfiguration(BootstrapConfiguration configuration);
        @BindsInstance Builder rootPasswordReset(RootPasswordReset rootPasswordReset);
        @BindsInstance Builder signingMaterial(SigningMaterialSet material);
        @BindsInstance Builder keyMaterial(OrionKeyMaterial material);
        @BindsInstance Builder sshHostKeyProvider(KeyPairProvider provider);
        @BindsInstance Builder nativeGitRepositoryProvider(NativeGitRepositoryFactory repositoryProvider);
        @BindsInstance Builder s3Transport(S3Transport transport);
        @BindsInstance Builder eventManager(OrionEventManager events);
        @BindsInstance Builder configuredRepositoryFactory(ConfiguredNativeGitRepositoryFactory factory);

        default Builder defaultBootstrapConfiguration() {
            BootstrapConfiguration configuration = new BootstrapConfiguration();
            OrionEventManager events = new OrionEventManager();
            S3Transport transport = new S3Transport();
            ConfiguredNativeGitRepositoryFactory configured =
                    new ConfiguredNativeGitRepositoryFactory(
                            NativeGitRepositoryBackend.inMemory(events), transport, events);
            NativeGitRepositoryFactory repositoryProvider =
                    new NativeGitRepositoryFactory(configured);
            repositoryProvider.resolveProvisional(
                    NativeGitRepositoryFactory.CONFIGURATION_SOURCE,
                    configuration.getBootstrap().getConfiguration(),
                    true);
            return bootstrapConfiguration(configuration)
                    .rootPasswordReset(new RootPasswordReset(false))
                    .signingMaterial(KeyMaterialBootstrap.signingMaterial(
                            configuration.getBootstrap().getKeyMaterial()))
                    .keyMaterial(OrionKeyMaterial.unavailable())
                    .sshHostKeyProvider(session -> {
                        throw new IllegalStateException("SSH host key material is not available");
                    })
                    .nativeGitRepositoryProvider(repositoryProvider)
                    .configuredRepositoryFactory(configured)
                    .s3Transport(transport)
                    .eventManager(events);
        }
    }
}
