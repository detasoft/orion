package pro.deta.orion.component;

import dagger.Module;
import dagger.Provides;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.BootstrapContext;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.decision.DecisionRegistry;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.keymaterial.ConfigurationCipherCapability;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.acl.storage.AccessControlStorage;
import pro.deta.orion.acl.storage.AccessControlStorageResolver;
import pro.deta.orion.agent.server.AgentSessionServer;
import pro.deta.orion.agent.server.connection.AgentControlHandler;
import pro.deta.orion.lifecycle.state.AggregateStateMachine;
import pro.deta.orion.schema.config.ConfigurationProvider;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.util.ConfigurationContext;

/**
 * Provides runtime services. Decision authorization is a temporary permissive stub;
 * task 02/01 must replace it with current hierarchical access checks.
 */
@Module
public class OrionRuntimeModule {
    private static final int MAX_PENDING_DECISIONS = 1024;

    @Provides
    @Singleton
    static DecisionRegistry decisionRegistry(OrionExecutor executor) {
        return new DecisionRegistry(MAX_PENDING_DECISIONS, executor, (actor, scope) -> true);
    }

    @Provides
    @Named("bootstrap-proxies")
    static Runnable bootstrapProxies(AccessControlStorage storage,
            ProxyAwareNativeGitRepositoryProvider provider, ConfigurationCipherCapability cipher,
            ConfigurationSecrets secrets, OrionDesiredState desiredState, OrionAccessControlServiceImpl acl) {
        return () -> {
            BootstrapContext.adoptProxies(storage, provider, cipher);
            acl.reload("bootstrap proxy adoption");
            provider.activate(() -> desiredState.current().document(), secrets);
        };
    }

    @Provides
    @Singleton
    static ConfigurationSecrets configurationSecrets(OrionDesiredState desiredState,
            ConfigurationCipherCapability cipher) {
        return new ConfigurationSecrets(() -> desiredState.current().document(), cipher);
    }

    @Provides
    static NativeGitRepositoryProvider nativeGitRepositoryProvider(ProxyAwareNativeGitRepositoryProvider provider) {
        return provider;
    }

    @Provides
    @Singleton
    static OrionConfiguration orionConfiguration(ConfigurationProvider configurationProvider) {
        return configurationProvider.readConfiguration();
    }

    @Provides
    @Singleton
    @Named("runtime")
    static AggregateStateMachine runtimeStateMachine(OrionRuntimeStateMachine stateMachine) {
        return stateMachine.aggregateStateMachine();
    }

    @Provides
    @Singleton
    static AgentSessionServer agentSessionServer(ConfigurationContext configuration) {
        return new AgentSessionServer(configuration.getBaseDir().resolve("agent-session-server"));
    }

    @Provides
    @Singleton
    static AgentControlHandler agentControlHandler(AgentSessionServer server) {
        return server;
    }

    @Provides
    OrionAccessControlService orionAccessControlService(
            OrionAccessControlServiceImpl orionAccessControlService) {
        return orionAccessControlService;
    }

    @Provides
    @Singleton
    static AccessControlStorage accessControlStorage(
            AccessControlStorageResolver accessControlStorageResolver) {
        return accessControlStorageResolver.resolve();
    }
}
